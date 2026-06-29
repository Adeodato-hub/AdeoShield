package es.adeodato.dnsguardian.security

import android.content.Context

object AppBlockManager {
    private const val PREFS = "guardian_blocked_apps"
    private const val KEY_SET = "blocked_packages"

    fun getBlocked(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SET, emptySet()) ?: emptySet()

    fun setBlocked(ctx: Context, packages: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SET, packages).apply()
    }

    fun toggle(ctx: Context, pkg: String) {
        val current = getBlocked(ctx).toMutableSet()
        if (pkg in current) current.remove(pkg) else current.add(pkg)
        setBlocked(ctx, current)
    }
}
