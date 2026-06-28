package es.adeodato.dnsguardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Se dispara cuando el movil termina de arrancar.
 * Su unica tarea es volver a lanzar el GuardianService,
 * para que la vigilancia del DNS siga activa tras un reinicio.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Solo nos interesa el evento de arranque completado.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("DNSGuardian", "Arranque detectado: relanzando el guardian.")

            val serviceIntent = Intent(context, GuardianService::class.java)

            // A partir de Android 8 los servicios en segundo plano
            // deben arrancarse como servicio en primer plano.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}