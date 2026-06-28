package es.adeodato.dnsguardian.security

object GuardState {
    @Volatile private var unlockedUntil: Long = 0L

    // Tope de seguridad: aunque no se cierre Ajustes, la gracia caduca sola.
    private const val GRACE_MS = 30_000L  // 30 s

    fun grantAccess() { unlockedUntil = System.currentTimeMillis() + GRACE_MS }
    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    /** Re-arma el candado de inmediato (al salir de Ajustes). */
    fun lockNow() { unlockedUntil = 0L }
}