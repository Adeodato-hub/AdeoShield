package es.adeodato.dnsguardian.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.adeodato.dnsguardian.security.AppBlockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Fondo  = Color(0xFF0E2A3B)
private val Acento = Color(0xFF38BDF8)
private val Rojo   = Color(0xFFF87171)
private val Ambar  = Color(0xFFFBBF24)

// ── Protecciones del sistema ──────────────────────────────────────────────────

private data class SysItem(
    val key: String,
    val emoji: String,
    val label: String,
    val desc: String
)

private val SYSTEM_ITEMS = listOf(
    SysItem(AppBlockManager.SYS_SETTINGS,      "⚙️",  "Ajustes del sistema",
            "Requiere PIN para abrir cualquier sección de Ajustes"),
    SysItem(AppBlockManager.SYS_ACCESSIBILITY, "♿",  "Accesibilidad",
            "PIN extra al acceder a Accesibilidad (aunque Ajustes esté desbloqueado)"),
    SysItem(AppBlockManager.SYS_VPN,           "🔒", "Configuración VPN",
            "PIN extra para la sección VPN dentro de Ajustes"),
    SysItem(AppBlockManager.SYS_DNS,           "🌐", "DNS Privado",
            "PIN extra para la sección DNS Privado dentro de Ajustes"),
    SysItem(AppBlockManager.SYS_DEV_OPTIONS,   "⚠️",  "Opciones de desarrollador",
            "PIN extra para activar ADB u otras opciones de desarrollador")
)

// ── Apps de usuario ───────────────────────────────────────────────────────────

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

// ── Activity ──────────────────────────────────────────────────────────────────

class AppBlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val pm     = packageManager
        val ownPkg = packageName

        setContent {
            var apps    by remember { mutableStateOf<List<AppInfo>?>(null) }
            var blocked by remember { mutableStateOf(AppBlockManager.getBlocked(this)) }

            LaunchedEffect(Unit) {
                apps = withContext(Dispatchers.IO) { loadUserApps(pm, ownPkg) }
            }

            AppBlockScreen(
                apps    = apps,
                blocked = blocked,
                onToggle = { key ->
                    AppBlockManager.toggle(this, key)
                    blocked = AppBlockManager.getBlocked(this)
                },
                onBack = { finish() }
            )
        }
    }
}

// ── UI ────────────────────────────────────────────────────────────────────────

@Composable
private fun AppBlockScreen(
    apps: List<AppInfo>?,
    blocked: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Fondo)
    ) {
        // Cabecera
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Volver", color = Acento, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text       = "Bloqueo de acceso",
                    color      = Color.White,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                val userBlocked = blocked.count { !it.startsWith("_sys_") }
                val sysBlocked  = blocked.count { it.startsWith("_sys_") }
                Text(
                    text     = "$userBlocked apps · $sysBlocked protecciones del sistema",
                    color    = Acento,
                    fontSize = 12.sp
                )
            }
        }

        HorizontalDivider(color = Color(0xFF1E3D50))

        LazyColumn {
            // ── Sección: protecciones del sistema ─────────────────────────────
            item {
                SectionHeader(
                    title = "PROTECCIONES DEL SISTEMA",
                    subtitle = "Estas opciones protegen los Ajustes del dispositivo"
                )
            }
            items(SYSTEM_ITEMS, key = { it.key }) { item ->
                SysRow(
                    item      = item,
                    isBlocked = item.key in blocked,
                    onToggle  = { onToggle(item.key) }
                )
                HorizontalDivider(color = Color(0xFF1A2E3B), thickness = 0.5.dp)
            }

            // ── Sección: apps de usuario ──────────────────────────────────────
            item {
                SectionHeader(
                    title    = "APLICACIONES DE USUARIO",
                    subtitle = when (apps) {
                        null -> "Cargando…"
                        else -> "${apps.size} apps instaladas"
                    }
                )
            }
            when {
                apps == null -> item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = Acento) }
                }
                apps.isEmpty() -> item {
                    Text(
                        "No hay apps de usuario instaladas.",
                        color    = Color(0xFFB9C7D1),
                        modifier = Modifier.padding(16.dp)
                    )
                }
                else -> items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app       = app,
                        isBlocked = app.packageName in blocked,
                        onToggle  = { onToggle(app.packageName) }
                    )
                    HorizontalDivider(color = Color(0xFF1A2E3B), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1F2E))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = title,    color = Acento, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(text = subtitle, color = Color(0xFF6A8090), fontSize = 11.sp)
    }
}

@Composable
private fun SysRow(item: SysItem, isBlocked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(if (isBlocked) Color(0xFF1A0A00) else Fondo)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = item.emoji, fontSize = 24.sp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = item.label,
                color      = if (isBlocked) Ambar else Color.White,
                fontSize   = 14.sp,
                fontWeight = if (isBlocked) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text     = item.desc,
                color    = Color(0xFF6A8090),
                fontSize = 11.sp
            )
        }
        Switch(
            checked         = isBlocked,
            onCheckedChange = { onToggle() },
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = Ambar,
                uncheckedThumbColor = Color(0xFF6A8090),
                uncheckedTrackColor = Color(0xFF1E3D50)
            )
        )
    }
}

@Composable
private fun AppRow(app: AppInfo, isBlocked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(if (isBlocked) Color(0xFF1E0A0A) else Fondo)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap             = app.icon,
            contentDescription = app.label,
            modifier           = Modifier.size(40.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = app.label,
                color      = if (isBlocked) Rojo else Color.White,
                fontSize   = 14.sp,
                fontWeight = if (isBlocked) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text     = app.packageName,
                color    = Color(0xFF6A8090),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked         = isBlocked,
            onCheckedChange = { onToggle() },
            colors          = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = Rojo,
                uncheckedThumbColor = Color(0xFF6A8090),
                uncheckedTrackColor = Color(0xFF1E3D50)
            )
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun loadUserApps(pm: PackageManager, ownPackage: String): List<AppInfo> =
    pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { info ->
            info.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
            info.packageName != ownPackage
        }
        .mapNotNull { info ->
            try {
                AppInfo(
                    packageName = info.packageName,
                    label       = pm.getApplicationLabel(info).toString(),
                    icon        = pm.getApplicationIcon(info.packageName).toBitmap().asImageBitmap()
                )
            } catch (e: Exception) { null }
        }
        .sortedBy { it.label.lowercase() }

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w   = intrinsicWidth.coerceAtLeast(1)
    val h   = intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}
