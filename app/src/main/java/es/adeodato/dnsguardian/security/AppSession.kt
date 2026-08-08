package es.adeodato.dnsguardian.security

/**
 * Sesion de la app ya autenticada por PIN. Vive SOLO en memoria del
 * proceso -- nunca se persiste a disco, asi que un proceso nuevo (p.ej.
 * tras reiniciar el dispositivo y que BootReceiver relance la VPN)
 * arranca siempre sin sesion heredada.
 *
 * Timeout de INACTIVIDAD, no absoluto: cada interaccion del usuario
 * (ver GuardedActivity.onUserInteraction) llama a touch(), que renueva
 * la ventana -- un uso activo continuado nunca fuerza el PIN a media
 * sesion. Si la app queda quieta (pantalla encendida, sin ningun toque)
 * mas de inactivityTimeoutMs, la sesion caduca sola; el sondeo periodico
 * de GuardedActivity (mientras la Activity esta RESUMED) es lo que
 * detecta esa caducidad y relanza el PIN de forma proactiva, sin
 * esperar a un onResume que no va a llegar si la Activity nunca sale de
 * primer plano.
 *
 * Triple invalidacion, la que llegue antes:
 *  - por inactividad (inactivityTimeoutMs sin ningun touch())
 *  - explicita al pasar la app entera a background (ver DNSGuardianApp,
 *    via ProcessLifecycleOwner)
 *  - explicita en el arranque (ver BootReceiver)
 */
object AppSession {
    @Volatile private var authenticatedUntil: Long = 0L

    /** Configurable: ventana de inactividad antes de exigir el PIN de nuevo. */
    @Volatile var inactivityTimeoutMs: Long = 300_000L // 5 min

    fun markAuthenticated() {
        authenticatedUntil = System.currentTimeMillis() + inactivityTimeoutMs
    }

    /** Renueva la ventana SOLO si ya habia sesion valida -- nunca autentica por si sola. */
    fun touch() {
        if (isAuthenticated()) {
            authenticatedUntil = System.currentTimeMillis() + inactivityTimeoutMs
        }
    }

    fun isAuthenticated(): Boolean = System.currentTimeMillis() < authenticatedUntil
    fun invalidate() {
        authenticatedUntil = 0L
    }
}
