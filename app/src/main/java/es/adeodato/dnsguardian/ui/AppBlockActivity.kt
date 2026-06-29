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
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val Fondo   = Color(0xFF0E2A3B)
private val Tarjeta = Color(0xFF163345)
private val Acento  = Color(0xFF38BDF8)
private val Verde   = Color(0xFF34D399)
private val Rojo    = Color(0xFFF87171)

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

class AppBlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val pm = packageManager
        val ownPkg = packageName

        setContent {
            var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
            var blocked by remember { mutableStateOf(AppBlockManager.getBlocked(this)) }

            LaunchedEffect(Unit) {
                apps = withContext(Dispatchers.IO) { loadUserApps(pm, ownPkg) }
            }

            AppBlockScreen(
                apps    = apps,
                blocked = blocked,
                onToggle = { pkg ->
                    AppBlockManager.toggle(this, pkg)
                    blocked = AppBlockManager.getBlocked(this)
                },
                onBack = { finish() }
            )
        }
    }
}

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
                    text       = "Apps bloqueadas",
                    color      = Color.White,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text     = if (apps == null) "Cargando…"
                               else "${blocked.size} de ${apps.size} bloqueadas",
                    color    = Acento,
                    fontSize = 12.sp
                )
            }
        }

        HorizontalDivider(color = Color(0xFF1E3D50))

        when {
            apps == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Acento)
                }
            }
            apps.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay apps de usuario instaladas.", color = Color(0xFFB9C7D1))
                }
            }
            else -> {
                LazyColumn {
                    items(apps, key = { it.packageName }) { app ->
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
            bitmap      = app.icon,
            contentDescription = app.label,
            modifier    = Modifier.size(40.dp)
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
                checkedThumbColor       = Color.White,
                checkedTrackColor       = Rojo,
                uncheckedThumbColor     = Color(0xFF6A8090),
                uncheckedTrackColor     = Color(0xFF1E3D50)
            )
        )
    }
}

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
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}
