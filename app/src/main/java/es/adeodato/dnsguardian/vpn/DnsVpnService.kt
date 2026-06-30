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
        const val TAG       = "DnsVpnService"
        const val CHANNEL_ID   = "dns_guardian_vpn"
        private const val NOTIF_ID = 2
        const val ACTION_STOP  = "es.adeodato.dnsguardian.STOP_VPN"

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

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dohClient = DoHClient(this)
        createNotificationChannel()
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
                val len = input.read(buffer)
                if (len <= 0) continue
                val packet = buffer.copyOf(len)
                executor.submit { handlePacket(packet, output) }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Tunnel error: ${e.javaClass.simpleName}: ${e.message}", e)
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
