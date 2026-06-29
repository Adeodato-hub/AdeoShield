package es.adeodato.dnsguardian

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.adeodato.dnsguardian.security.PinManager
import es.adeodato.dnsguardian.ui.PinActivity
import es.adeodato.dnsguardian.vpn.DnsVpnService

class MainActivity : ComponentActivity() {

    private var vpnGranted     by mutableStateOf(false)
    private var adminActive    by mutableStateOf(false)
    private var pinSet         by mutableStateOf(false)
    private var privateDnsMode by mutableStateOf("off")
    private var showDnsWarning by mutableStateOf(false)

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startDnsVpn()
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (VpnService.prepare(this) == null) startDnsVpn()

        setContent {
            GuardianScreen(
                vpnGranted     = vpnGranted,
                adminActive    = adminActive,
                pinSet         = pinSet,
                privateDnsMode = privateDnsMode,
                showDnsWarning = showDnsWarning,
                onActivateVpn     = ::requestVpnPermission,
                onActivateAdmin   = ::requestAdmin,
                onSetupPin        = ::openPinSetup,
                onDismissWarning  = { showDnsWarning = false },
                onOpenDnsSettings = ::openPrivateDnsSettings
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ── Acciones ──────────────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startDnsVpn()
    }

    private fun startDnsVpn() {
        startForegroundService(Intent(this, DnsVpnService::class.java))
    }

    private fun requestAdmin() {
        val comp = ComponentName(this, GuardianAdminReceiver::class.java)
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Activa el administrador para impedir que se desinstale la protección DNS."
            )
        })
    }

    private fun openPinSetup() {
        startActivity(
            Intent(this, PinActivity::class.java)
                .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SETUP)
        )
    }

    private fun openPrivateDnsSettings() {
        try {
            startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        vpnGranted     = VpnService.prepare(this) == null
        adminActive    = isAdminActive(this)
        pinSet         = PinManager.isPinSet(this)
        privateDnsMode = Settings.Global.getString(contentResolver, "private_dns_mode") ?: "off"
        // Solo mostramos el diálogo bloqueante cuando hay un proveedor externo configurado
        showDnsWarning = privateDnsMode == "hostname"
    }
}

private fun isAdminActive(ctx: Context): Boolean {
    val dpm  = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val comp = ComponentName(ctx, GuardianAdminReceiver::class.java)
    return dpm.isAdminActive(comp)
}

// ── Colores ───────────────────────────────────────────────────────────────────

private val Fondo   = Color(0xFF0E2A3B)
private val Tarjeta = Color(0xFF163345)
private val Acento  = Color(0xFF38BDF8)
private val Verde   = Color(0xFF34D399)
private val Ambar   = Color(0xFFFBBF24)
private val Rojo    = Color(0xFFF87171)

// ── Pantalla principal ────────────────────────────────────────────────────────

@Composable
fun GuardianScreen(
    vpnGranted: Boolean,
    adminActive: Boolean,
    pinSet: Boolean,
    privateDnsMode: String,
    showDnsWarning: Boolean,
    onActivateVpn: () -> Unit,
    onActivateAdmin: () -> Unit,
    onSetupPin: () -> Unit,
    onDismissWarning: () -> Unit,
    onOpenDnsSettings: () -> Unit
) {
    val privateDnsOk = privateDnsMode != "hostname"
    val todo = vpnGranted && adminActive && pinSet && privateDnsOk

    // Diálogo bloqueante: DNS privado con proveedor externo inutiliza el filtro
    if (showDnsWarning) {
        AlertDialog(
            onDismissRequest = onDismissWarning,
            containerColor   = Color(0xFF163345),
            title = {
                Text(
                    text       = "DNS Privado incompatible",
                    color      = Rojo,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text  = "Tienes DNS Privado configurado con un proveedor externo. " +
                            "Esto hace que el filtro DNS quede completamente inactivo: " +
                            "las consultas nunca pasan por DNSGuardian.\n\n" +
                            "Ve a Ajustes → Conexiones → Más ajustes de conexión → " +
                            "DNS Privado y ponlo en \"Desactivado\".",
                    color = Color(0xFFB9C7D1)
                )
            },
            confirmButton = {
                Button(
                    onClick = onOpenDnsSettings,
                    colors  = ButtonDefaults.buttonColors(containerColor = Acento)
                ) {
                    Text(
                        text       = "Ir a Ajustes",
                        color      = Color(0xFF0E2A3B),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissWarning) {
                    Text(text = "Ahora no", color = Color(0xFF88A0B0))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Fondo)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = if (todo) "🛡️" else "⚠️", fontSize = 64.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text       = "DNS GUARDIAN",
            color      = Color.White,
            fontSize   = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text       = if (todo) "PROTECCIÓN COMPLETA" else "CONFIGURACIÓN PENDIENTE",
            color      = if (todo) Verde else Ambar,
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(32.dp))

        ProtectionCard(
            emoji    = "🔒",
            title    = "Filtro DNS (VPN)",
            desc     = if (vpnGranted) "VPN local activa — AdGuard Family"
                       else            "Toca para activar el filtro DNS",
            ok       = vpnGranted,
            label    = "Activar",
            onAction = onActivateVpn
        )

        Spacer(Modifier.height(12.dp))

        ProtectionCard(
            emoji      = "🌐",
            title      = "DNS Privado del sistema",
            desc       = when (privateDnsMode) {
                "hostname"      -> "Proveedor externo activo — FILTRO INACTIVO"
                "opportunistic" -> "Modo automático — puede ralentizar el filtro"
                else            -> "Desactivado — correcto"
            },
            ok         = privateDnsOk,
            label      = "Ajustar",
            onAction   = onOpenDnsSettings,
            errorColor = privateDnsMode == "hostname"
        )

        Spacer(Modifier.height(12.dp))

        ProtectionCard(
            emoji    = "🛡️",
            title    = "Administrador del dispositivo",
            desc     = if (adminActive) "Desinstalación bloqueada"
                       else             "Sin esto la app puede borrarse",
            ok       = adminActive,
            label    = "Activar",
            onAction = onActivateAdmin
        )

        Spacer(Modifier.height(12.dp))

        ProtectionCard(
            emoji    = "🔑",
            title    = "PIN parental",
            desc     = if (pinSet) "PIN configurado"
                       else        "Sin PIN cualquiera accede a Ajustes",
            ok       = pinSet,
            label    = if (pinSet) "Cambiar" else "Configurar",
            onAction = onSetupPin
        )

        Spacer(Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(12.dp),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A2E3B))
        ) {
            Text(
                text     = "⚡ Samsung: ve a Ajustes → Mantenimiento dispositivo → Batería → " +
                           "Gestión energía apps → DNSGuardian → Sin restricciones.\n" +
                           "Esto evita que One UI mate el filtro en segundo plano.",
                color    = Color(0xFFB9C7D1),
                fontSize = 12.sp,
                modifier = Modifier.padding(14.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text      = "Adeodato · Red doméstica · AdGuard Family DNS",
            color     = Acento,
            fontSize  = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProtectionCard(
    emoji: String,
    title: String,
    desc: String,
    ok: Boolean,
    label: String,
    onAction: () -> Unit,
    errorColor: Boolean = false
) {
    val indicador = when {
        ok         -> Verde
        errorColor -> Rojo
        else       -> Ambar
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = Tarjeta)
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text       = "● ",
                        color      = indicador,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text       = title,
                        color      = Color.White,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(text = desc, color = Color(0xFFB9C7D1), fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onAction,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = if (!ok) Acento else Color(0xFF1E3D50)
                ),
                modifier = Modifier.size(width = 88.dp, height = 36.dp)
            ) {
                Text(
                    text       = label,
                    fontSize   = 12.sp,
                    color      = if (!ok) Color(0xFF0E2A3B) else Color(0xFF88A0B0),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
