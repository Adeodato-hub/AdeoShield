package es.adeodato.dnsguardian

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import es.adeodato.dnsguardian.security.AccessibilityWatchdogWorker
import es.adeodato.dnsguardian.security.AppBlockManager
import es.adeodato.dnsguardian.security.AppSession
import java.util.concurrent.TimeUnit

class DNSGuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppBlockManager.ensureDefaults(this)

        // Invalida la sesion autenticada en cuanto la app ENTERA (no una
        // Activity suelta navegando internamente) pasa a background.
        // ProcessLifecycleOwner, a diferencia de Activity.onPause/onStop,
        // no dispara al moverse entre Activities de la propia app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppSession.invalidate()
            }
        })

        scheduleAccessibilityWatchdog()
    }

    // Respaldo del sondeo de 60s en DnsVpnService (que solo corre con la
    // VPN activa): cierra, en la medida de lo posible, el hueco "VPN
    // parada Y Accesibilidad desactivada a la vez" (ver AccessibilityWatchdog.kt).
    // enqueueUniquePeriodicWork + KEEP evita duplicar el trabajo si la app
    // se reinicia con uno ya encolado -- WorkManager persiste sus
    // trabajos en su propia base de datos y sobrevive a reinicios del
    // dispositivo sin configuracion adicional por nuestra parte.
    private fun scheduleAccessibilityWatchdog() {
        val request = PeriodicWorkRequestBuilder<AccessibilityWatchdogWorker>(
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ACCESSIBILITY_WATCHDOG_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        private const val ACCESSIBILITY_WATCHDOG_WORK_NAME = "accessibility_watchdog"
    }
}
