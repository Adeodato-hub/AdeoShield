package es.adeodato.dnsguardian

import android.app.Application
import es.adeodato.dnsguardian.security.AppBlockManager

class DNSGuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppBlockManager.ensureDefaults(this)
    }
}
