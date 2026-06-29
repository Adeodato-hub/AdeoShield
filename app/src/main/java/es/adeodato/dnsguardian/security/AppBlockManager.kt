package es.adeodato.dnsguardian.security

import android.content.Context

object AppBlockManager {
    private const val PREFS = "guardian_blocked_apps"
    private const val KEY_SET = "blocked_packages"

    // Claves sintéticas para protecciones del sistema (no son package names reales).
    // Se almacenan en el mismo Set que las apps normales.
    const val SYS_SETTINGS     = "_sys_settings_"      // Toda la app de Ajustes
    const val SYS_ACCESSIBILITY = "_sys_accessibility_" // Sub-página Accesibilidad
    const val SYS_VPN          = "_sys_vpn_"            // Sub-página VPN
    const val SYS_DNS          = "_sys_dns_"            // Sub-página DNS Privado
    const val SYS_DEV_OPTIONS  = "_sys_devopt_"         // Opciones de desarrollador

    fun getBlocked(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SET, emptySet()) ?: emptySet()

    fun setBlocked(ctx: Context, packages: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SET, packages).commit()
    }

    fun toggle(ctx: Context, pkg: String) {
        val current = getBlocked(ctx).toMutableSet()
        if (pkg in current) current.remove(pkg) else current.add(pkg)
        setBlocked(ctx, current)
    }
}
