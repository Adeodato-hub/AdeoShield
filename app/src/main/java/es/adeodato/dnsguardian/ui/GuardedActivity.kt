package es.adeodato.dnsguardian.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import es.adeodato.dnsguardian.security.AppSession
import es.adeodato.dnsguardian.security.PinManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity que exige sesion autenticada (AppSession) en CADA onResume,
 * no solo al arrancar desde el LAUNCHER. Cubre TODOS los caminos que
 * puedan traer aqui directamente -- notificaciones, shortcuts, tiles,
 * deep links futuros, y volver desde "Apps recientes" (que reanuda una
 * instancia ya viva por onResume, sin pasar por onCreate).
 *
 * Ademas de onResume, hace sondeo periodico MIENTRAS la Activity esta
 * RESUMED (via repeatOnLifecycle, se cancela solo en pausa) para pillar
 * la caducidad por inactividad si el usuario se queda quieto en la app:
 * onUserInteraction() no puede bloquear el toque que lo dispara, asi
 * que sin este sondeo la sesion caducada solo se detectaria en el
 * SIGUIENTE onResume real -- que no llega si la Activity nunca sale de
 * primer plano. onUserInteraction() renueva la ventana en cada toque
 * (ver AppSession.touch()), asi que un uso activo continuado nunca
 * dispara el sondeo -- solo salta si la app queda abandonada de verdad.
 *
 * FLAG_SECURE evita que el contenido aparezca en la miniatura de
 * "Apps recientes" o en capturas de pantalla mientras esta activa.
 */
abstract class GuardedActivity : ComponentActivity() {
    private var awaitingPin = false

    private val pinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        awaitingPin = false
        if (result.resultCode == Activity.RESULT_OK) {
            AppSession.markAuthenticated()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(SESSION_POLL_MS)
                    checkSession()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkSession()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppSession.touch()
    }

    private fun checkSession() {
        if (awaitingPin) return
        if (!PinManager.isPinSet(this)) return // nada que proteger todavia
        if (AppSession.isAuthenticated()) return

        awaitingPin = true
        pinLauncher.launch(
            Intent(this, PinActivity::class.java)
                .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
        )
    }

    private companion object {
        const val SESSION_POLL_MS = 15_000L // 15s
    }
}
