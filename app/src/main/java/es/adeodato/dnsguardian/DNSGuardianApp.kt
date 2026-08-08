package es.adeodato.dnsguardian

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import es.adeodato.dnsguardian.security.AppBlockManager
import es.adeodato.dnsguardian.security.AppSession

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
    }
}
