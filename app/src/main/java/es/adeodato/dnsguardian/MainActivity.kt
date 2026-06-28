package es.adeodato.dnsguardian

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.adeodato.dnsguardian.ui.PinActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            startForegroundService(Intent(this, GuardianService::class.java))
        } catch (_: Exception) { }

        enableEdgeToEdge()
        setContent {
            GuardianScreen()
        }
    }
}

fun pedirAdmin(context: Context) {
    val componente = ComponentName(context, GuardianAdminReceiver::class.java)
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componente)
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Activa el administrador para impedir que se desinstale la proteccion."
        )
    }
    context.startActivity(intent)
}

@Composable
fun GuardianScreen() {
    val context = LocalContext.current
    val fondo = Color(0xFF0E2A3B)
    val acento = Color(0xFF38BDF8)
    val verde = Color(0xFF34D399)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(fondo)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "\uD83D\uDEE1\uFE0F", fontSize = 72.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "DNS GUARDIAN", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "PROTECCION ACTIVA", color = verde, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "Esta red esta vigilada.\n" +
                    "El filtro familiar permanece activo,\n" +
                    "aunque intentes desactivarlo.",
            color = Color(0xFFB9C7D1), fontSize = 16.sp, textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { pedirAdmin(context) }) { Text("Reforzar proteccion") }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            context.startActivity(
                Intent(context, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SETUP)
            )
        }) { Text("Configurar PIN") }
        Spacer(modifier = Modifier.height(40.dp))
        Text(text = "Adeodato · Red domestica", color = acento, fontSize = 12.sp)
    }
}