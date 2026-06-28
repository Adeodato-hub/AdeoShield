package es.adeodato.dnsguardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import es.adeodato.dnsguardian.vpn.DnsVpnService

/**
 * Se dispara cuando el dispositivo termina de arrancar.
 * Relanza el DnsVpnService solo si el permiso VPN ya fue concedido
 * previamente (VpnService.prepare devuelve null).
 * Si no, el usuario deberá abrir la app manualmente para activarlo.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Solo podemos arrancar la VPN si el permiso ya está concedido.
        // Si prepare() devuelve null → permiso OK → iniciamos.
        if (VpnService.prepare(context) != null) {
            Log.w("BootReceiver", "Permiso VPN no concedido aún. El usuario debe abrir la app.")
            return
        }

        Log.i("BootReceiver", "Arranque detectado: relanzando DnsVpnService.")
        context.startForegroundService(Intent(context, DnsVpnService::class.java))
    }
}
