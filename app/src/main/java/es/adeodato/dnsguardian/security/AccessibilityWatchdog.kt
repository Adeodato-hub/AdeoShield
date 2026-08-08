package es.adeodato.dnsguardian.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import es.adeodato.dnsguardian.service.GuardianAccessibilityService
import es.adeodato.dnsguardian.ui.PinGateActivity

/**
 * Detecta que GuardianAccessibilityService fue desactivado desde Ajustes
 * (o cualquier otra via) y avisa. check() es la FUENTE DE VERDAD: se llama
 * periodicamente desde un componente que vive independientemente del
 * servicio de Accesibilidad (DnsVpnService), asi que sigue detectando la
 * desactivacion aunque el servicio muera antes de poder avisar el mismo.
 * alertaInmediata() es solo un adelanto best-effort desde onUnbind().
 *
 * No cubre: modo seguro, ADB (que desactiva el servicio sin pasar por
 * Ajustes -- eso ya lo cubre el ContentObserver de ADB_ENABLED en el
 * propio servicio, y solo si ADB no estaba ya activo de antes), ni el
 * hueco entre que se desactiva y el siguiente check().
 *
 * LIMITACION CONOCIDA (dependencia circular): check() se llama desde
 * DnsVpnService -- si la VPN esta parada, este vigilante tampoco corre.
 * Y la propia Accesibilidad es justo lo que impide (via tipoCritico, con
 * SYS_VPN en el set bloqueado) que se pare la VPN sin PIN. Mientras la
 * VPN este activa -- el estado normal de la app en uso -- la deteccion
 * funciona; el hueco real es "VPN parada Y Accesibilidad desactivada a
 * la vez" (p.ej. tras un fallo/kill de la VPN por el sistema), que hoy
 * no lo cubre nada aqui.
 */
object AccessibilityWatchdog {
    private const val CHANNEL_ID = "dns_guardian_accessibility_alert"
    private const val NOTIF_ID = 3
    private const val PREFS = "guardian_accessibility_watchdog"
    private const val KEY_WARNING_SHOWN = "warning_shown"

    fun isServiceEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val target = ComponentName(ctx, GuardianAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(target, ignoreCase = true) }
    }

    fun check(ctx: Context) {
        val protegido = PinManager.isPinSet(ctx)
        val activo = isServiceEnabled(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (protegido && !activo) {
            if (!prefs.getBoolean(KEY_WARNING_SHOWN, false)) {
                mostrarAviso(ctx)
                prefs.edit().putBoolean(KEY_WARNING_SHOWN, true).apply()
            }
        } else if (prefs.getBoolean(KEY_WARNING_SHOWN, false)) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
            prefs.edit().putBoolean(KEY_WARNING_SHOWN, false).apply()
        }
    }

    fun alertaInmediata(ctx: Context) = mostrarAviso(ctx)

    private fun mostrarAviso(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Avisos de protección", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Alerta si se desactiva una protección crítica" }
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, PinGateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Protección de Accesibilidad desactivada")
            .setContentText("El bloqueo de apps y de Ajustes críticos ha dejado de funcionar.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pi)
            // Ongoing, SIN setAutoCancel: si fuera descartable (deslizar) y
            // el usuario la descarta, KEY_WARNING_SHOWN seguiria en true y
            // el siguiente check() (activo=false todavia) no la repondria,
            // al pensar que ya se habia avisado -- el aviso desaparaceria
            // para siempre mientras el servicio siga desactivado.
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }
}
