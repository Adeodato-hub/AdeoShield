package es.adeodato.dnsguardian.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import es.adeodato.dnsguardian.MainActivity
import es.adeodato.dnsguardian.security.PinManager

/**
 * Puerta de entrada de la app (Activity LAUNCHER).
 * Antes de mostrar nada, exige el PIN si existe.
 * No usa onResume/finishAffinity (causa del crash anterior).
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
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}