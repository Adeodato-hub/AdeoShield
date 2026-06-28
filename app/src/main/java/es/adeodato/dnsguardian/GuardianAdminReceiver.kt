package es.adeodato.dnsguardian

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Administrador de dispositivo de DNS Guardian.
 * Mientras este admin este activo, la app NO se puede desinstalar
 * sin desactivarlo antes en Ajustes -> Seguridad -> Administradores.
 */
class GuardianAdminReceiver : DeviceAdminReceiver() {

    // Cuando el usuario ACTIVA el administrador.
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DNSGuardian", "Administrador de dispositivo ACTIVADO.")
        Toast.makeText(context, "Proteccion reforzada activada", Toast.LENGTH_SHORT).show()
    }

    // Aviso que se muestra cuando intentan DESACTIVAR el administrador.
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Si desactivas el administrador, la proteccion DNS dejara de estar blindada."
    }

    // Cuando el usuario DESACTIVA el administrador.
    override fun onDisabled(context: Context, intent: Intent) {
        Log.w("DNSGuardian", "Administrador de dispositivo DESACTIVADO.")
    }
}