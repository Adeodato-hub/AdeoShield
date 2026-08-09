package es.adeodato.dnsguardian.service

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import es.adeodato.dnsguardian.GuardianAdminReceiver
import es.adeodato.dnsguardian.R
import es.adeodato.dnsguardian.security.AccessibilityWatchdog
import es.adeodato.dnsguardian.security.AppBlockManager
import es.adeodato.dnsguardian.security.GuardState
import es.adeodato.dnsguardian.security.PinManager
import es.adeodato.dnsguardian.ui.PinActivitySettingsGuard

class GuardianAccessibilityService : AccessibilityService() {

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.samsung.accessibility",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    private val launcherHints = listOf("launcher", "home")

    private val blockedApps: Set<String> get() = AppBlockManager.getBlocked(this)

    private val handler = Handler(Looper.getMainLooper())
    private var pinAbierto = false
    private var adbObserver: ContentObserver? = null

    // Tipo semántico de la última página crítica desbloqueada con PIN
    // ("accessibility", "vpn", "dns", "dev", "admin", o "").
    // Usar el tipo —no la clase ni el texto— garantiza estabilidad aunque
    // Samsung dispare eventos con pageText vacío o distinto en la misma página.
    private var lastCriticalType = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastCriticalType = ""
        registrarObservadorAdb()
        // El sondeo periodico de DnsVpnService es la fuente de verdad;
        // esto solo adelanta la cancelacion del aviso si ya estaba activo.
        AccessibilityWatchdog.check(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        adbObserver?.let { contentResolver.unregisterContentObserver(it) }
    }

    // Best-effort: corre justo al desligarse el servicio (p.ej. al apagar
    // el toggle en Ajustes), pero el proceso puede morir antes de que la
    // notificacion llegue a mostrarse. La fuente de verdad real es el
    // sondeo periodico de AccessibilityWatchdog.check() desde
    // DnsVpnService, que no depende de que este codigo llegue a correr.
    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityWatchdog.alertaInmediata(this)
        return super.onUnbind(intent)
    }

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
                        lanzarPin()
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

        if (esLanzador) { lastCriticalType = ""; GuardState.lockNow(); return }

        val tipoCrit = if (esAjustes) tipoCritico(className, pageText, blocked) else null
        val paginaCriticaBloqueada = tipoCrit != null
        val ajustesBloqueados = esAjustes && AppBlockManager.SYS_SETTINGS in blocked

        if (!esAppBloqueada && !ajustesBloqueados && !paginaCriticaBloqueada) return
        if (!PinManager.isPinSet(this)) return

        if (paginaCriticaBloqueada) {
            val mismoTipoConGracia = tipoCrit == lastCriticalType && GuardState.isUnlocked()
            if (mismoTipoConGracia) {
                // Pase único: consume la gracia en el primer evento de retorno
                // para que la siguiente visita exija PIN. pinAbierto bloquea los
                // eventos duplicados de Samsung durante 1200 ms.
                GuardState.lockNow()
                pinAbierto = true
                handler.postDelayed({ pinAbierto = false }, 1200)
                return
            }
            GuardState.lockNow()
        }

        if (GuardState.isUnlocked()) { pinAbierto = false; return }
        if (pinAbierto) return

        pinAbierto = true
        if (paginaCriticaBloqueada) lastCriticalType = tipoCrit!!
        lanzarPin()
        handler.postDelayed({ pinAbierto = false }, 1200)
    }

    private fun lanzarPin() {
        // PinActivitySettingsGuard, no PinActivity: clase separada con su
        // propia tarea aislada (taskAffinity + singleTask en el manifest)
        // para que finish() devuelva a Ajustes en vez de traer al frente
        // la tarea de la app -- ver comentario alli.
        startActivity(
            Intent(this, PinActivitySettingsGuard::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        )
    }

    // Devuelve el tipo semántico de la página si está habilitada como crítica,
    // o null si no aplica. El tipo es estable (no depende de texto ni clase exacta).
    private fun tipoCritico(className: String, pageText: String, blocked: Set<String>): String? {
        val isAccessibility = className.contains("accessibility") ||
                              pageText.contains("accesib") ||
                              pageText.contains("servicios instalados") ||
                              pageText.contains("installed services") ||
                              // La pantalla-detalle de UN servicio concreto (donde esta
                              // el switch real) suele vivir en un contenedor generico de
                              // Ajustes sin "accessibility" en el className ni en el
                              // titulo -- pero SIEMPRE muestra el label del servicio como
                              // titulo, sea cual sea el contenedor o la ruta de navegacion
                              // (lista o buscador de Ajustes directo al detalle).
                              pageText.contains(getString(R.string.accessibility_service_label).lowercase())
        val isVpn  = className.contains("vpn") || pageText == "vpn"
        val isDns  = className.contains("privatednssettings") || className.contains("privatedns") ||
                     pageText.contains("dns privado") || pageText.contains("private dns")
        val isDev  = className.contains("developeroptionsdashboard") ||
                     className.contains("developersettings") ||
                     className.contains("developmentoptions") ||
                     className.contains("development") ||
                     className.contains("wirelessdebugging") ||
                     className.contains("adbwifi") ||
                     pageText.contains("opciones de desarrollador") ||
                     pageText.contains("developer options") ||
                     pageText.contains("depuración usb") ||
                     pageText.contains("depuración inalámbrica") ||
                     pageText.contains("usb debugging") ||
                     pageText.contains("wireless debugging")
        // Pantallas de admin: PIN solo si el admin YA esta activo (entonces
        // hay algo que desactivar -- la amenaza real). Mientras no lo este,
        // conceder no es amenaza y todo el asistente (dialogo de explicacion
        // + SecDeviceAdminAdd) pasa sin PIN -- sin depender de className
        // especifico de cada pantalla del asistente (fragil, distinto por
        // OEM/version) ni de ninguna ventana de tiempo. "uninstall" queda
        // SIEMPRE protegido, pase lo que pase con el admin.
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminActivo = dpm.isAdminActive(ComponentName(this, GuardianAdminReceiver::class.java))
        // El dialogo de confirmar desinstalar se renderiza como
        // "android.app.AlertDialog" generico (sin "uninstall" en el
        // className) -- exige el className generico Y el texto, para no
        // disparar con cualquier pantalla que mencione "desinstalar" de
        // pasada (p.ej. una ficha de Play Store).
        val isUninstall = className.contains("uninstall") ||
                          (className.contains("alertdialog") &&
                           (pageText.contains("desinstalar") || pageText.contains("uninstall")))
        val adminScreen = className.contains("deviceadmin") || className.contains("device_admin") ||
                          (pageText.contains("administra") && pageText.contains("dispositivo"))
        val isAdmin = isUninstall || (adminScreen && adminActivo)
        return when {
            isAccessibility && AppBlockManager.SYS_ACCESSIBILITY in blocked -> "accessibility"
            isVpn           && AppBlockManager.SYS_VPN           in blocked -> "vpn"
            isDns           && AppBlockManager.SYS_DNS           in blocked -> "dns"
            isDev           && AppBlockManager.SYS_DEV_OPTIONS   in blocked -> "dev"
            isAdmin         && AppBlockManager.SYS_DEVICE_ADMIN  in blocked -> "admin"
            else -> null
        }
    }

    override fun onInterrupt() {}
}
