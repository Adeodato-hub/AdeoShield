package es.adeodato.dnsguardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Servicio guardian del DNS privado.
 * Vigila el ajuste del sistema y, si alguien lo cambia o lo apaga,
 * lo reescribe de inmediato al host de proteccion familiar.
 */
class GuardianService : Service() {

    companion object {
        // El host de proteccion que queremos imponer.
        const val DNS_HOSTNAME = "family-filter-dns.cleanbrowsing.org"

        // Claves del sistema para el DNS privado.
        private const val MODE_KEY = "private_dns_mode"
        private const val HOST_KEY = "private_dns_specifier"

        private const val CHANNEL_ID = "dns_guardian_channel"
        private const val NOTIF_ID = 1
        private const val TAG = "DNSGuardian"
    }

    private lateinit var observer: ContentObserver

    override fun onCreate() {
        super.onCreate()

        // 1. Notificacion obligatoria del servicio en primer plano.
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // 2. Aplicamos el DNS correcto al arrancar.
        enforceDns()

        // 3. Nos quedamos vigilando cambios en los ajustes del sistema.
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enforceDns()
            }
        }
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(MODE_KEY), false, observer
        )
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(HOST_KEY), false, observer
        )

        Log.i(TAG, "Guardian iniciado. Imponiendo: $DNS_HOSTNAME")
    }

    /**
     * Comprueba el DNS actual y, si no coincide con el nuestro,
     * lo reescribe. Requiere el permiso WRITE_SECURE_SETTINGS (via ADB).
     */
    private fun enforceDns() {
        try {
            val currentMode = Settings.Global.getString(contentResolver, MODE_KEY)
            val currentHost = Settings.Global.getString(contentResolver, HOST_KEY)

            val ok = currentMode == "hostname" && currentHost == DNS_HOSTNAME
            if (!ok) {
                Settings.Global.putString(contentResolver, MODE_KEY, "hostname")
                Settings.Global.putString(contentResolver, HOST_KEY, DNS_HOSTNAME)
                Log.w(TAG, "DNS alterado -> restaurado a $DNS_HOSTNAME")
            }
        } catch (e: SecurityException) {
            // Pasa si aun no se ha concedido WRITE_SECURE_SETTINGS por ADB.
            Log.e(TAG, "Falta el permiso WRITE_SECURE_SETTINGS. Concedelo por ADB.", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS Guardian",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Mantiene activo el filtro DNS familiar."
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Proteccion DNS activa")
            .setContentText("Vigilando el filtro familiar.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    // Si el sistema mata el servicio, que lo vuelva a crear.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(observer)
    }

    // No usamos binding; es un servicio independiente.
    override fun onBind(intent: Intent?): IBinder? = null
}