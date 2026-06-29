package es.adeodato.dnsguardian.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

    // Se lee en cada evento para reflejar cambios del padre en tiempo real.
    private val blockedApps: Set<String> get() = AppBlockManager.getBlocked(this)

    private val handler = Handler(Looper.getMainLooper())
    private var pinAbierto = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString()?.lowercase() ?: ""

        // Nunca bloquear nuestra propia app (es donde aparece el PIN).
        if (pkg == packageName) { pinAbierto = false; return }

        val blocked = blockedApps
        val esLanzador = launcherHints.any { pkg.contains(it, true) }
        val esAjustes  = pkg in settingsPackages || pkg.contains("settings", true)
        val esAppBloqueada = pkg in blocked

        val pageText = event.text?.joinToString(" ")?.lowercase() ?: ""

        // Al llegar al HOME, re-armamos el candado para la próxima vez.
        if (esLanzador) { GuardState.lockNow(); return }

        // ¿Es una sub-página crítica que el padre ha configurado como bloqueada?
        val paginaCriticaBloqueada = esAjustes && paginaCriticaBloqueada(className, pageText, blocked)
        // ¿Está bloqueada la app de Ajustes completa?
        val ajustesBloqueados = esAjustes && AppBlockManager.SYS_SETTINGS in blocked

        // Si no hay ningún motivo para pedir PIN, salir sin hacer nada.
        if (!esAppBloqueada && !ajustesBloqueados && !paginaCriticaBloqueada) return

        if (!PinManager.isPinSet(this)) return

        // Las páginas críticas configuradas como bloqueadas invalidan la gracia:
        // el PIN se pide siempre, incluso dentro de una sesión de Ajustes abierta.
        if (paginaCriticaBloqueada) GuardState.lockNow()

        // Ventana de gracia activa: el padre acaba de desbloquear, dejar pasar.
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

    // Detecta si la clase de la ventana actual corresponde a una sub-página
    // crítica que el padre ha marcado como bloqueada en AppBlockActivity.
    private fun paginaCriticaBloqueada(className: String, pageText: String, blocked: Set<String>): Boolean {
        val isAccessibility = className.contains("accessibility") ||
                              pageText.contains("accesib") ||
                              pageText.contains("accessibility") ||
                              pageText.contains("servicios instalados") ||
                              pageText.contains("installed services")
        val isVpn  = className.contains("vpn") || pageText == "vpn"
        val isDns  = className.contains("privatednssettings") || className.contains("privatedns") ||
                     pageText.contains("dns privado") || pageText.contains("private dns")
        val isDev  = className.contains("developeroptionsdashboard") ||
                     className.contains("developersettings") ||
                     pageText.contains("opciones de desarrollador") ||
                     pageText.contains("developer options")
        val isAdmin = className.contains("deviceadmin") || className.contains("device_admin")
        return when {
            isAccessibility -> AppBlockManager.SYS_ACCESSIBILITY in blocked
            isVpn           -> AppBlockManager.SYS_VPN           in blocked
            isDns           -> AppBlockManager.SYS_DNS           in blocked
            isDev           -> AppBlockManager.SYS_DEV_OPTIONS   in blocked
            isAdmin         -> AppBlockManager.SYS_SETTINGS      in blocked
            else            -> false
        }
    }

    override fun onInterrupt() {}
}
