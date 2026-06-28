package es.adeodato.dnsguardian.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.adeodato.dnsguardian.security.GuardState
import es.adeodato.dnsguardian.security.PinManager

private val Marino = Color(0xFF0E2A3B)
private val MarinoTop = Color(0xFF143A52)
private val Acento = Color(0xFF38BDF8)
private val Verde = Color(0xFF34D399)
private val TextoTenue = Color(0xFFB9C7D1)

class PinActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_REASON = "extra_reason"
        const val MODE_SETUP = "setup"
        const val MODE_VERIFY = "verify"
        const val REASON_SETTINGS_GUARD = "settings_guard"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val isSettingsGuard = intent.getStringExtra(EXTRA_REASON) == REASON_SETTINGS_GUARD
        val requestedSetup = intent.getStringExtra(EXTRA_MODE) == MODE_SETUP
        val pinExists = PinManager.isPinSet(this)

        setContent {
            MaterialTheme {
                PinScaffold {
                    if (isSettingsGuard) {
                        BackHandler { goHome() }
                    }

                    when {
                        requestedSetup && !pinExists -> {
                            PinSetupScreen(
                                title = "Crea el PIN parental",
                                subtitle = "Elige un PIN de al menos 4 dígitos",
                                onSaved = { setResult(Activity.RESULT_OK); finish() }
                            )
                        }

                        requestedSetup && pinExists -> {
                            var verified by remember { mutableStateOf(false) }
                            if (!verified) {
                                PinVerifyScreen(
                                    title = "Introduce el PIN actual",
                                    subtitle = "Verifica tu identidad para cambiarlo",
                                    onSuccess = { verified = true }
                                )
                            } else {
                                PinSetupScreen(
                                    title = "Crea el PIN nuevo",
                                    subtitle = "Elige un PIN de al menos 4 dígitos",
                                    onSaved = { setResult(Activity.RESULT_OK); finish() }
                                )
                            }
                        }

                        else -> {
                            PinVerifyScreen(
                                title = "Protección activa",
                                subtitle = "Introduce el PIN parental para continuar",
                                onSuccess = {
                                    if (isSettingsGuard) {
                                        GuardState.grantAccess()
                                        abrirAjustes()
                                    }
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun abrirAjustes() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }
}

@Composable
private fun PinScaffold(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MarinoTop, Marino)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

@Composable
private fun Cabecera(title: String, subtitle: String) {
    Text(text = "🛡️", fontSize = 56.sp)
    Spacer(Modifier.height(12.dp))
    Text(
        text = title,
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = subtitle,
        color = TextoTenue,
        fontSize = 14.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun Tarjeta(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF15324A),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun PinSetupScreen(title: String, subtitle: String, onSaved: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Cabecera(title, subtitle)
    Tarjeta {
        PinField(value = pin, onValueChange = { pin = it }, label = "PIN")
        Spacer(Modifier.height(14.dp))
        PinField(value = confirm, onValueChange = { confirm = it }, label = "Repite el PIN")
        Spacer(Modifier.height(22.dp))
        BotonAccion(
            texto = "Guardar PIN",
            enabled = pin.length >= 4 && confirm.length >= 4
        ) {
            when {
                pin.length < 4 ->
                    Toast.makeText(context, "El PIN debe tener al menos 4 dígitos", Toast.LENGTH_SHORT).show()
                pin != confirm ->
                    Toast.makeText(context, "Los PIN no coinciden", Toast.LENGTH_SHORT).show()
                else -> try {
                    PinManager.setPin(context, pin)
                    Toast.makeText(context, "PIN guardado", Toast.LENGTH_SHORT).show()
                    onSaved()
                } catch (e: Exception) {
                    Toast.makeText(context, "Error al guardar el PIN. Inténtalo de nuevo.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun PinVerifyScreen(title: String, subtitle: String, onSuccess: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }

    Cabecera(title, subtitle)
    Tarjeta {
        PinField(value = pin, onValueChange = { pin = it }, label = "PIN parental")
        Spacer(Modifier.height(22.dp))
        BotonAccion(texto = "Continuar", enabled = pin.length >= 4) {
            if (pin.length < 4) {
                Toast.makeText(context, "Introduce el PIN (mínimo 4 dígitos)", Toast.LENGTH_SHORT).show()
                return@BotonAccion
            }
            try {
                when (val r = PinManager.verifyPin(context, pin)) {
                    is PinManager.Result.Ok -> onSuccess()
                    is PinManager.Result.Wrong ->
                        Toast.makeText(context, "PIN incorrecto. Intentos: ${r.remaining}", Toast.LENGTH_SHORT).show()
                    is PinManager.Result.Locked ->
                        Toast.makeText(context, "Bloqueado. Espera ${r.msLeft / 1000} s", Toast.LENGTH_SHORT).show()
                    is PinManager.Result.NoPin ->
                        Toast.makeText(context, "No hay PIN configurado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error al verificar. Inténtalo de nuevo.", Toast.LENGTH_SHORT).show()
            }
            pin = ""
        }
    }
}

@Composable
private fun BotonAccion(texto: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Acento,
            contentColor = Marino,
            disabledContainerColor = Color(0xFF2A4A60),
            disabledContentColor = TextoTenue
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text(texto, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PinField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Acento,
            unfocusedBorderColor = Color(0xFF3A5A72),
            focusedLabelColor = Acento,
            unfocusedLabelColor = TextoTenue,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Acento
        ),
        modifier = Modifier.fillMaxWidth()
    )
}