package es.adeodato.dnsguardian.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import es.adeodato.dnsguardian.security.GuardState
import es.adeodato.dnsguardian.security.PinManager
import es.adeodato.dnsguardian.ui.PinActivity

class GuardianAccessibilityService : AccessibilityService() {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    private val launcherHints = listOf("launcher", "home")

    private val blockedApps = setOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var pinAbierto = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // Nuestra propia app: nunca se bloquea (es la pantalla del PIN).
        if (pkg == packageName) { pinAbierto = false; return }

        val esAjustes = pkg in settingsPackages || pkg.contains("settings", true)
        val esAppBloqueada = pkg in blockedApps
        val esLanzador = launcherHints.any { pkg.contains(it, true) }

        // Al llegar al HOME, re-armamos el candado.
        if (esLanzador) { GuardState.lockNow(); return }

        if (!esAjustes && !esAppBloqueada) return

        if (!PinManager.isPinSet(this)) return

        // Ventana de gracia tras meter el PIN: dejar pasar.
        if (GuardState.isUnlocked()) { pinAbierto = false; return }

        if (pinAbierto) return
        pinAbierto = true

        // INMEDIATO: lanzar el PIN ya, sin pasar por HOME ni esperar.
        // PinActivity (FLAG_SECURE + NEW_TASK) tapa Ajustes al instante.
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

    override fun onInterrupt() {}
}