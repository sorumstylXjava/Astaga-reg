package com.javapro.ui.screens

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class FpsSession(
    val packageName : String,
    val appLabel    : String,
    val date        : String,
    val avgFps      : Float,
    val powerW      : Float,
    val duration    : Long,
    val icon        : Drawable?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsStatsScreen(navController: NavController) {
    val context    = LocalContext.current
    val pm         = context.packageManager

    val deviceInfo = remember { TweakExecutor.getDeviceInfo(context) }
    val platform   = remember { deviceInfo["soc"] ?: android.os.Build.HARDWARE.uppercase() }
    val model      = remember { android.os.Build.MODEL }
    val sdk        = remember { "SDK(${android.os.Build.VERSION.SDK_INT})" }

    var sessions   by remember { mutableStateOf<List<FpsSession>>(emptyList()) }
    var recording  by remember { mutableStateOf(false) }
    var currentFps by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        sessions = withContext(Dispatchers.IO) {
            listOf(
                FpsSession(
                    packageName = "com.example.game",
                    appLabel    = "Sample Game",
                    date        = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                    avgFps      = 0f,
                    powerW      = 0f,
                    duration    = 0L,
                    icon        = try { pm.getApplicationIcon("com.example.game") } catch (_: Exception) { null }
                )
            ).filter { false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("FPS Stats", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            DeviceInfoCard(platform = platform, model = model, sdk = sdk)

            Spacer(Modifier.height(12.dp))

            if (recording) {
                LiveFpsCard(fps = currentFps, onStop = { recording = false })
                Spacer(Modifier.height(12.dp))
            }

            if (sessions.isEmpty()) {
                EmptySessionsCard()
            } else {
                Text(
                    "Recorded Sessions",
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier   = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.packageName + it.date }) { session ->
                        SessionCard(session = session, onDelete = {
                            sessions = sessions.filter { it !== session }
                        })
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            BottomBar(
                onClearAll = { sessions = emptyList() },
                onRecord   = { recording = !recording }
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceInfoCard(platform: String, model: String, sdk: String) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            DeviceInfoColumn(
                icon  = Icons.Default.Memory,
                label = "Platform",
                value = platform,
                tint  = Color(0xFF7B68EE)
            )
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(
                icon  = Icons.Default.PhoneAndroid,
                label = "Model",
                value = model.take(12),
                tint  = Color(0xFF4FC3F7)
            )
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(
                icon  = Icons.Default.Android,
                label = "OS",
                value = sdk,
                tint  = Color(0xFF81C784)
            )
        }
    }
}

@Composable
private fun DeviceInfoColumn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier         = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(0.12f))
                .border(BorderStroke(0.8.dp, tint.copy(0.3f)), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LiveFpsCard(fps: Float, onStop: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val accentColor = when {
        fps >= 55f -> Color(0xFF66BB6A)
        fps >= 30f -> Color(0xFFFFCA28)
        else       -> Color(0xFFEF5350)
    }
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = accentColor.copy(0.08f),
        border = BorderStroke(1.dp, accentColor.copy(0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier         = Modifier
                        .size(10.dp)
                        .background(accentColor.copy(alpha), CircleShape)
                )
                Column {
                    Text("Recording…", fontSize = 11.sp, color = accentColor, fontWeight = FontWeight.Bold)
                    Text(
                        if (fps > 0f) "%.1f FPS".format(fps) else "Waiting for data",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor
                    )
                }
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(0.15f))
                    .size(40.dp)
            ) {
                Icon(Icons.Default.Stop, null, tint = accentColor)
            }
        }
    }
}

@Composable
private fun EmptySessionsCard() {
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp))
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Default.Speed,
                null,
                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                "No sessions recorded yet",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)
            )
            Text(
                "Tap Record to start tracking FPS",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f)
            )
        }
    }
}

@Composable
private fun SessionCard(session: FpsSession, onDelete: () -> Unit) {
    val context = LocalContext.current
    val iconBitmap = remember(session.packageName) {
        session.icon?.toBitmap()?.asImageBitmap()
    }
    val fpsColor = when {
        session.avgFps >= 55f -> Color(0xFF66BB6A)
        session.avgFps >= 30f -> Color(0xFFFFCA28)
        else                  -> Color(0xFF78909C)
    }
    val duration = remember(session.duration) {
        val s = session.duration / 1000
        if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = MaterialTheme.colorScheme.surface,
        border   = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap             = iconBitmap,
                    contentDescription = null,
                    modifier           = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier         = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.appLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.avgFps > 0f)
                        Text("%.2f".format(session.avgFps), fontSize = 11.sp, color = fpsColor, fontWeight = FontWeight.SemiBold)
                    if (session.powerW > 0f)
                        Text("%.2fW".format(session.powerW), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                if (session.duration > 0L) duration else "--",
                fontSize   = 11.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier   = Modifier.padding(end = 8.dp)
            )

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomBar(onClearAll: () -> Unit, onRecord: () -> Unit) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipItem(icon = Icons.Default.Android, label = "All") {}
                FilterChipItem(icon = Icons.Default.AllInclusive, label = "Launcher") {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick  = onClearAll,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .size(40.dp)
                ) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick  = onRecord,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .size(40.dp)
                ) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun FilterChipItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape    = CircleShape,
        color    = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
