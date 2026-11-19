package es.adeodato.dnsguardian.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import es.adeodato.dnsguardian.security.AccessibilityWatchdog
import es.adeodato.dnsguardian.ui.PinGateActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {

    companion object {
        const val TAG       = "DnsVpnService"
        const val CHANNEL_ID   = "dns_guardian_vpn"
        private const val NOTIF_ID = 2
        const val ACTION_STOP  = "es.adeodato.dnsguardian.STOP_VPN"

        // Intervalo del sondeo de AccessibilityWatchdog -- fuente de verdad
        // de la deteccion de "servicio de Accesibilidad desactivado".
        // LIMITACION: solo corre mientras esta Activity/servicio (la VPN)
        // esta viva -- ver comentario de la dependencia circular en
        // AccessibilityWatchdog.kt.
        private const val ACCESSIBILITY_CHECK_MS = 60_000L // 1 min

        // IPs cuyo tráfico se intercepta. Las IPs de los servidores DoH reales
        // (94.140.14.15 / 94.140.15.16) no están aquí para que salgan por la
        // red física y no entren en bucle.
        val INTERCEPT_IPS = listOf(
            "10.0.0.1",
            "8.8.8.8",  "8.8.4.4",
            "1.1.1.1",  "1.0.0.1",
            "9.9.9.9",  "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
            "94.140.14.14",   "94.140.15.15"
        )
    }

    private var tunFd: ParcelFileDescriptor? = null
    private val running  = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var dohClient: DoHClient
    private val handler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            AccessibilityWatchdog.check(this@DnsVpnService)
            handler.postDelayed(this, ACCESSIBILITY_CHECK_MS)
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dohClient = DoHClient(this)
        createNotificationChannel()
        handler.post(watchdogRunnable)
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, buildNotification())
        if (!running.getAndSet(true)) executor.submit { startTunnel() }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke")
        shutdown()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        handler.removeCallbacks(watchdogRunnable)
        shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }

    // ── Tunnel ────────────────────────────────────────────────────────────────

    private fun startTunnel() {
        Log.i(TAG, "startTunnel")
        try {
            val builder = Builder()
                .setSession("AdeoShield")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.1")
                // El fd que devuelve establish() es NO bloqueante por
                // defecto: read() devolvia 0 sin datos y el bucle de abajo
                // giraba en vacio (espera activa, ~1 nucleo al 100 % y el
                // telefono sin poder dormir). Bloqueante, el hilo duerme en
                // el kernel hasta que llega un paquete. La parada sigue
                // funcionando: shutdown() pone running=false y cierra tunFd,
                // lo que hace fallar el read() bloqueado y sale del bucle.
                .setBlocking(true)

            INTERCEPT_IPS.forEach { builder.addRoute(it, 32) }

            tunFd = builder.establish() ?: run {
                Log.e(TAG, "establish() returned null — missing VPN permission?")
                running.set(false)
                return
            }

            val input  = FileInputStream(tunFd!!.fileDescriptor)
            val output = FileOutputStream(tunFd!!.fileDescriptor)
            val buffer = ByteArray(32_767)

            while (running.get()) {
                val len = input.read(buffer)  // bloquea hasta que llega un paquete
                if (len < 0) break            // EOF: el tun se ha cerrado
                if (len == 0) continue
                val packet = buffer.copyOf(len)
                executor.submit { handlePacket(packet, output) }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Tunnel error: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            // Traza de verificacion de la parada: con read() bloqueante,
            // confirma que cerrar tunFd desbloquea el hilo y este termina.
            Log.i(TAG, "startTunnel: bucle del tun terminado")
        }
    }

    // ── Procesado de paquetes ─────────────────────────────────────────────────

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 28) return

        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return

        val ihl      = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF

        if (protocol != 17) return
        if (packet.size < ihl + 8) return

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                       (packet[ihl + 3].toInt() and 0xFF)

        if (dstPort != 53) return  // descarte silencioso: bloquea DoH/DoT externo

        val srcIp   = packet.copyOfRange(12, 16)
        val dstIp   = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or
                       (packet[ihl + 1].toInt() and 0xFF)
        val dns     = packet.copyOfRange(ihl + 8, packet.size)

        val response = dohClient.query(dns)
        if (response == null) {
            Log.e(TAG, "DoH null for '${extractDomainName(dns)}' — client will timeout")
            return
        }

        val reply = PacketUtils.buildUdpPacket(
            srcIp   = dstIp,
            dstIp   = srcIp,
            srcPort = 53,
            dstPort = srcPort,
            payload = response
        )

        try {
            synchronized(output) { output.write(reply) }
        } catch (e: Exception) {
            Log.e(TAG, "TUN write error: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractDomainName(dns: ByteArray): String {
        return try {
            if (dns.size < 13) return "<short>"
            val sb = StringBuilder()
            var pos = 12
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 == 0xC0) { sb.append("…"); break }
                if (sb.isNotEmpty()) sb.append('.')
                if (pos + 1 + len > dns.size) break
                sb.append(String(dns, pos + 1, len, Charsets.US_ASCII))
                pos += 1 + len
            }
            if (sb.isEmpty()) "<root>" else sb.toString()
        } catch (e: Exception) { "<error>" }
    }

    private fun shutdown() {
        running.set(false)
        tunFd?.close()
        tunFd = null
    }

    // ── Notificación ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "AdeoShield", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Filtro DNS familiar activo"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        // Apunta a PinGateActivity, no a MainActivity: correccion adicional
        // de defensa en profundidad (la principal es el gate en
        // MainActivity.onResume() via GuardedActivity, que ya cubriria
        // esto igualmente). FLAG_UPDATE_CURRENT es imprescindible aqui:
        // sin el, un PendingIntent ya cacheado por el sistema con el mismo
        // requestCode (0) desde una version anterior de la app seguiria
        // apuntando a MainActivity aunque se recompile el codigo.
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, PinGateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
