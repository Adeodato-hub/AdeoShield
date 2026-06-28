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

/**
 * Servicio VPN local que intercepta consultas DNS y las reenvía a
 * AdGuard Family DNS vía DoH, bloqueando contenido adulto sin necesidad
 * de permisos ADB ni reseteo de fábrica.
 *
 * PASO 1 – Esqueleto: el servicio arranca, muestra notificación y
 *           devuelve START_STICKY. La lógica de paquetes viene en el Paso 2.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG        = "DnsVpnService"
        const  val CHANNEL_ID        = "dns_guardian_vpn"
        private const val NOTIF_ID   = 2

        const val ACTION_STOP = "es.adeodato.dnsguardian.STOP_VPN"

        /**
         * IPs que enrutamos a través del TUN:
         *  - 10.0.0.1      → nuestro servidor DNS virtual
         *  - Resto         → DNS sin filtro conocidos (bloqueamos DoH/DoT a ellos)
         *
         * IMPORTANTE: 94.140.14.15 y 94.140.15.16 (AdGuard Family) NO están
         * aquí: son las IPs que usamos nosotros y deben salir por la red real.
         */
        val INTERCEPT_IPS = listOf(
            "10.0.0.1",                           // DNS virtual propio
            "8.8.8.8",   "8.8.4.4",               // Google DNS (sin filtro)
            "1.1.1.1",   "1.0.0.1",               // Cloudflare DNS (sin filtro)
            "9.9.9.9",   "149.112.112.112",        // Quad9 (sin filtro)
            "208.67.222.222", "208.67.220.220",    // OpenDNS (sin filtro)
            "94.140.14.14",   "94.140.15.15"       // AdGuard DNS (sin filtro)
        )
    }

    private var tunFd: ParcelFileDescriptor? = null
    private val running   = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var dohClient: DoHClient

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dohClient = DoHClient(this)
        createNotificationChannel()
        Log.i(TAG, "Servicio creado.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        if (!running.getAndSet(true)) {
            executor.submit { startTunnel() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revocada por el sistema.")
        shutdown()
    }

    override fun onDestroy() {
        shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }

    // ── Tunnel ────────────────────────────────────────────────────────────────

    private fun startTunnel() {
        try {
            val builder = Builder()
                .setSession("DNSGuardian")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.1")

            INTERCEPT_IPS.forEach { builder.addRoute(it, 32) }

            tunFd = builder.establish() ?: run {
                Log.e(TAG, "establish() devolvió null — ¿falta permiso VPN?")
                return
            }

            Log.i(TAG, "Túnel activo. Procesando paquetes…")

            val input  = FileInputStream(tunFd!!.fileDescriptor)
            val output = FileOutputStream(tunFd!!.fileDescriptor)
            val buffer = ByteArray(32_767)

            while (running.get()) {
                val len = input.read(buffer)
                if (len <= 0) continue
                val packet = buffer.copyOf(len)
                executor.submit { handlePacket(packet, output) }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Error en el túnel: ${e.message}")
        }
    }

    // ── Procesado de paquetes ─────────────────────────────────────────────────

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        // Necesitamos al menos 20 bytes de cabecera IPv4 + 8 de UDP
        if (packet.size < 28) return

        // Solo IPv4
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return

        val ihl      = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF

        // Solo UDP (17)
        if (protocol != 17 || packet.size < ihl + 8) return

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                       (packet[ihl + 3].toInt() and 0xFF)

        // Paquetes a otros puertos (443, 853) se descartan en silencio:
        // bloquea DoH y DoT a los servidores sin filtro enrutados.
        if (dstPort != 53) return

        val srcIp   = packet.copyOfRange(12, 16)
        val dstIp   = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or
                       (packet[ihl + 1].toInt() and 0xFF)
        val dns     = packet.copyOfRange(ihl + 8, packet.size)

        val response = dohClient.query(dns) ?: return

        val reply = PacketUtils.buildUdpPacket(
            srcIp  = dstIp,     // El "servidor DNS" responde desde la IP destino original
            dstIp  = srcIp,     // hacia el cliente
            srcPort = 53,
            dstPort = srcPort,
            payload = response
        )

        synchronized(output) { output.write(reply) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun shutdown() {
        running.set(false)
        tunFd?.close()
        tunFd = null
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "DNS Guardian",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Filtro DNS familiar activo" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Protección DNS activa")
            .setContentText("Filtrando contenido adulto vía AdGuard Family")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

}
