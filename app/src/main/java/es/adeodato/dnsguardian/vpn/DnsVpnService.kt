package es.adeodato.dnsguardian.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import es.adeodato.dnsguardian.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {

    companion object {
        const  val TAG      = "DnsVpnService"
        const  val CHANNEL_ID  = "dns_guardian_vpn"
        private const val NOTIF_ID = 2
        const  val ACTION_STOP = "es.adeodato.dnsguardian.STOP_VPN"

        /**
         * IPs enrutadas por el TUN.
         *  - 10.0.0.1          → nuestro servidor DNS virtual
         *  - Resto             → DNS sin filtro; bloqueamos DoH/DoT a ellos (descarte silencioso)
         *
         * NO incluir 94.140.14.15 / 94.140.15.16 (AdGuard Family): son los que
         * usamos para el DoH y deben salir por la red física, no por el TUN.
         */
        val INTERCEPT_IPS = listOf(
            "10.0.0.1",
            "8.8.8.8",   "8.8.4.4",
            "1.1.1.1",   "1.0.0.1",
            "9.9.9.9",   "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
            "94.140.14.14",   "94.140.15.15"
        )
    }

    private var tunFd: ParcelFileDescriptor? = null
    private val running  = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var dohClient: DoHClient

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dohClient = DoHClient(this)
        createNotificationChannel()
        Log.i(TAG, "onCreate: servicio creado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification())
        if (!running.getAndSet(true)) {
            Log.i(TAG, "onStartCommand: lanzando túnel en segundo plano.")
            executor.submit { startTunnel() }
        } else {
            Log.i(TAG, "onStartCommand: servicio ya en marcha, ignorando.")
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke: el sistema ha revocado el permiso VPN.")
        shutdown()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy.")
        shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }

    // ── Tunnel ────────────────────────────────────────────────────────────────

    private fun startTunnel() {
        Log.i(TAG, "startTunnel: configurando interfaz TUN…")
        Log.i(TAG, "  Rutas que interceptaremos: $INTERCEPT_IPS")

        try {
            val builder = Builder()
                .setSession("DNSGuardian")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.1")

            INTERCEPT_IPS.forEach { builder.addRoute(it, 32) }

            tunFd = builder.establish() ?: run {
                Log.e(TAG, "establish() devolvió null — ¿falta el permiso VPN?")
                running.set(false)
                return
            }
            Log.i(TAG, "Interfaz TUN activa. Fd=${tunFd!!.fd}")

            // ── Autotest DoH ─────────────────────────────────────────────────
            // Verificamos conectividad con el provider DoH antes de entrar en
            // el bucle de lectura. Así sabemos si el problema es la red o el TUN.
            executor.submit { selfTestDoH() }

            // ── Bucle principal de lectura ────────────────────────────────────
            val input  = FileInputStream(tunFd!!.fileDescriptor)
            val output = FileOutputStream(tunFd!!.fileDescriptor)
            val buffer = ByteArray(32_767)
            var pktsRead = 0

            Log.i(TAG, "Entrando en bucle de lectura de paquetes…")
            while (running.get()) {
                val len = input.read(buffer)
                if (len <= 0) continue
                pktsRead++
                if (pktsRead <= 5 || pktsRead % 50 == 0) {
                    Log.d(TAG, "Paquete #$pktsRead leído del TUN: ${len}B")
                }
                val packet = buffer.copyOf(len)
                executor.submit { handlePacket(packet, output) }
            }
            Log.i(TAG, "Saliendo del bucle de lectura (running=false).")
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Error en el túnel: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // ── Autotest ──────────────────────────────────────────────────────────────

    private fun selfTestDoH() {
        Log.i(TAG, "=== AUTOTEST DoH: consultando 'google.com' directamente ===")
        try {
            Thread.sleep(1500) // dar tiempo a que la red se estabilice
            val query = buildDnsQuery("google.com")
            Log.i(TAG, "  Query DNS construida: ${query.size}B")
            val response = dohClient.query(query)
            if (response != null) {
                Log.i(TAG, "  ✓ AUTOTEST OK: respuesta DoH recibida (${response.size}B)")
            } else {
                Log.e(TAG, "  ✗ AUTOTEST FALLIDO: dohClient.query() devolvió null")
                Log.e(TAG, "    → El DoH no funciona. Ver logs de DoHClient para detalle.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "  ✗ AUTOTEST excepción: ${e.message}", e)
        }
        Log.i(TAG, "=== FIN AUTOTEST ===")
    }

    /** Construye una consulta DNS tipo A para [domain] en wire-format. */
    private fun buildDnsQuery(domain: String): ByteArray {
        val labels = domain.split(".")
        val nameBytes = labels.sumOf { it.length + 1 } + 1 // len+label por cada parte + 0x00 final
        val buf = ByteArray(12 + nameBytes + 4)
        buf[0] = 0xAB.toByte(); buf[1] = 0xCD.toByte() // ID arbitrario
        buf[2] = 0x01; buf[3] = 0x00                   // Flags: query estándar con RD
        buf[4] = 0x00; buf[5] = 0x01                   // QDCOUNT = 1
        var pos = 12
        for (label in labels) {
            buf[pos++] = label.length.toByte()
            label.forEach { c -> buf[pos++] = c.code.toByte() }
        }
        buf[pos++] = 0x00          // Fin del nombre
        buf[pos++] = 0x00; buf[pos++] = 0x01  // QTYPE = A
        buf[pos++] = 0x00; buf[pos]   = 0x01  // QCLASS = IN
        return buf
    }

    // ── Procesado de paquetes ─────────────────────────────────────────────────

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 28) {
            Log.v(TAG, "Paquete demasiado corto (${packet.size}B), ignorado.")
            return
        }

        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) {
            Log.v(TAG, "Paquete IPv$version ignorado (solo IPv4).")
            return
        }

        val ihl      = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF

        if (protocol != 17) {
            Log.v(TAG, "Protocolo $protocol ignorado (solo UDP=17).")
            return
        }
        if (packet.size < ihl + 8) {
            Log.v(TAG, "Paquete UDP demasiado corto, ignorado.")
            return
        }

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                       (packet[ihl + 3].toInt() and 0xFF)
        val dstIpStr = packet.slice(16..19).joinToString(".") { (it.toInt() and 0xFF).toString() }

        if (dstPort != 53) {
            // Puerto no-DNS a una IP interceptada → descarte silencioso (bloquea DoH/DoT externo)
            Log.d(TAG, "Descartando paquete no-DNS: dstIp=$dstIpStr dstPort=$dstPort " +
                        "(bloqueo intencional DoH/DoT externo)")
            return
        }

        val srcIp   = packet.copyOfRange(12, 16)
        val dstIp   = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or
                       (packet[ihl + 1].toInt() and 0xFF)
        val dns     = packet.copyOfRange(ihl + 8, packet.size)

        val srcIpStr = srcIp.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val domain   = extractDomainName(dns)

        Log.d(TAG, "DNS query: '$domain'  src=$srcIpStr:$srcPort → $dstIpStr:53  payload=${dns.size}B")

        val response = dohClient.query(dns)
        if (response == null) {
            Log.e(TAG, "DoH null para '$domain' → cliente recibirá timeout DNS")
            return
        }
        Log.d(TAG, "DoH respondió ${response.size}B para '$domain' → escribiendo al TUN")

        val reply = PacketUtils.buildUdpPacket(
            srcIp   = dstIp,
            dstIp   = srcIp,
            srcPort = 53,
            dstPort = srcPort,
            payload = response
        )

        try {
            synchronized(output) { output.write(reply) }
            Log.d(TAG, "Escrito al TUN: ${reply.size}B (IP+UDP+DNS)")
        } catch (e: Exception) {
            Log.e(TAG, "Error escribiendo al TUN: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extrae el primer nombre de la sección de preguntas del mensaje DNS. */
    private fun extractDomainName(dns: ByteArray): String {
        return try {
            if (dns.size < 13) return "<muy corto>"
            val sb = StringBuilder()
            var pos = 12 // saltar cabecera DNS (12 bytes fijos)
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 == 0xC0) { sb.append("…(ptr)"); break } // puntero de compresión
                if (sb.isNotEmpty()) sb.append('.')
                if (pos + 1 + len > dns.size) break
                sb.append(String(dns, pos + 1, len, Charsets.US_ASCII))
                pos += 1 + len
            }
            if (sb.isEmpty()) "<raíz>" else sb.toString()
        } catch (e: Exception) { "<error:${e.message}>" }
    }

    private fun shutdown() {
        running.set(false)
        tunFd?.close()
        tunFd = null
    }

    // ── Notificación ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "DNS Guardian", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Filtro DNS familiar activo"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Protección DNS activa")
            .setContentText("Filtrando contenido adulto vía AdGuard Family")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
