package es.adeodato.dnsguardian.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import es.adeodato.dnsguardian.security.AppBlockManager
import es.adeodato.dnsguardian.security.GuardState
import es.adeodato.dnsguardian.security.PinManager
import es.adeodato.dnsguardian.ui.PinActivity

class GuardianAccessibilityService : AccessibilityService() {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.samsung.accessibility",          // app principal de Accesibilidad en Samsung
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    private val launcherHints = listOf("launcher", "home")

    private val blockedApps: Set<String> get() = AppBlockManager.getBlocked(this)

    private val handler = Handler(Looper.getMainLooper())
    private var pinAbierto = false
    private var adbObserver: ContentObserver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        registrarObservadorAdb()
    }

    override fun onDestroy() {
        super.onDestroy()
        adbObserver?.let { contentResolver.unregisterContentObserver(it) }
    }

    // Segunda línea de defensa: si ADB se activa por cualquier vía (incluso
    // sin pasar por la UI de Opciones de Desarrollador), se exige PIN.
    private fun registrarObservadorAdb() {
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val adbActivo = Settings.Global.getInt(
                    contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
                if (adbActivo &&
                    AppBlockManager.SYS_DEV_OPTIONS in blockedApps &&
                    PinManager.isPinSet(this@GuardianAccessibilityService)
                ) {
                    GuardState.lockNow()
                    if (!pinAbierto) {
                        pinAbierto = true
                        startActivity(Intent(this@GuardianAccessibilityService, PinActivity::class.java).apply {
                            putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
                            putExtra(PinActivity.EXTRA_REASON, PinActivity.REASON_SETTINGS_GUARD)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )
                        })
                        handler.postDelayed({ pinAbierto = false }, 1200)
                    }
                }
            }
        }
        adbObserver = obs
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, obs
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString()?.lowercase() ?: ""

        if (pkg == packageName) { pinAbierto = false; return }

        val blocked = blockedApps
        val esLanzador = launcherHints.any { pkg.contains(it, true) }
        val esAjustes  = pkg in settingsPackages || pkg.contains("settings", true)
        val esAppBloqueada = pkg in blocked

        val pageText = event.text?.joinToString(" ")?.lowercase() ?: ""

        if (esLanzador) { GuardState.lockNow(); return }

        val paginaCriticaBloqueada = esAjustes && paginaCriticaBloqueada(className, pageText, blocked)
        val ajustesBloqueados = esAjustes && AppBlockManager.SYS_SETTINGS in blocked

        if (!esAppBloqueada && !ajustesBloqueados && !paginaCriticaBloqueada) return

        if (!PinManager.isPinSet(this)) return

        if (paginaCriticaBloqueada) GuardState.lockNow()

        if (GuardState.isUnlocked()) { pinAbierto = false; return }

        if (pinAbierto) return
        pinAbierto = true

        startActivity(Intent(this, PinActivity::class.java).apply {
            putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
            putExtra(PinActivity.EXTRA_REASON, PinActivity.REASON_SETTINGS_GUARD)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        })
        handler.postDelayed({ pinAbierto = false }, 1200)
    }

    private fun paginaCriticaBloqueada(className: String, pageText: String, blocked: Set<String>): Boolean {
        val isAccessibility = className.contains("accessibility") ||
                              pageText.contains("accesib") ||
                              pageText.contains("accessibility") ||
                              pageText.contains("servicios instalados") ||
                              pageText.contains("installed services")
        val isVpn  = className.contains("vpn") || pageText == "vpn"
        val isDns  = className.contains("privatednssettings") || className.contains("privatedns") ||
                     pageText.contains("dns privado") || pageText.contains("private dns")
        // Primera línea: bloquea la página principal Y sub-páginas (depuración USB,
        // depuración inalámbrica, ADB Wi-Fi) por className y por título en español/inglés.
        val isDev  = className.contains("developeroptionsdashboard") ||
                     className.contains("developersettings") ||
                     className.contains("developmentoptions") ||
                     className.contains("wirelessdebugging") ||
                     className.contains("adbwifi") ||
                     pageText.contains("opciones de desarrollador") ||
                     pageText.contains("developer options") ||
                     pageText.contains("depuración usb") ||
                     pageText.contains("depuración inalámbrica") ||
                     pageText.contains("usb debugging") ||
                     pageText.contains("wireless debugging")
        val isAdmin = className.contains("deviceadmin") || className.contains("device_admin") ||
                      (pageText.contains("administra") && pageText.contains("dispositivo")) ||
                      className.contains("uninstall")
        return when {
            isAccessibility -> AppBlockManager.SYS_ACCESSIBILITY in blocked
            isVpn           -> AppBlockManager.SYS_VPN           in blocked
            isDns           -> AppBlockManager.SYS_DNS           in blocked
            isDev           -> AppBlockManager.SYS_DEV_OPTIONS   in blocked
            isAdmin         -> AppBlockManager.SYS_DEVICE_ADMIN  in blocked
            else            -> false
        }
    }

    override fun onInterrupt() {}
}
