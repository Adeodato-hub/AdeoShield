package es.adeodato.dnsguardian.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import es.adeodato.dnsguardian.MainActivity
import es.adeodato.dnsguardian.security.AppSession
import es.adeodato.dnsguardian.security.PinManager

/**
 * Puerta de entrada de la app (Activity LAUNCHER).
 * Antes de mostrar nada, exige el PIN si existe.
 * No usa onResume/finishAffinity (causa del crash anterior).
 *
 * FLAG_SECURE aunque esta Activity no dibuje UI propia (siempre delega
 * en PinActivity o en MainActivity) -- es la raiz de la tarea, asi que
 * su ventana existe brevemente antes del hand-off y podria quedar
 * capturada en la miniatura de "Apps recientes" si no se protege igual
 * que el resto de pantallas (ver GuardedActivity).
 */
class PinGateActivity : ComponentActivity() {

    private val pinLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                abrirApp()
            } else {
                // PIN incorrecto, atras o cancelado: cerrar la app. Sin bucles.
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Si la Activity se recrea (p. ej. rotacion) con el PIN en pantalla,
        // no relanzamos: esperamos el resultado pendiente.
        if (savedInstanceState != null) return

        // Aun no hay PIN: nada que proteger todavia. Entramos para poder crearlo.
        if (!PinManager.isPinSet(this)) {
            abrirApp()
            return
        }

        // Hay PIN: exigir verificacion antes de abrir la app.
        val intent = Intent(this, PinActivity::class.java)
            .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
        pinLauncher.launch(intent)
    }

    private fun abrirApp() {
        // Sesion valida ya aqui: evita que MainActivity.onResume() (via
        // GuardedActivity) vuelva a pedir el PIN justo despues de haberlo
        // verificado en esta misma puerta.
        AppSession.markAuthenticated()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}