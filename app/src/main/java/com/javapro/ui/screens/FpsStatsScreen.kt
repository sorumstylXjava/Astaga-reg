package com.javapro.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.javapro.fps.*
import com.javapro.fps.ui.RealtimeLineChart
import kotlinx.coroutines.delay

// ──────────────────────────────────────────────────────────────
// Entry screen (session list + live monitor)
// ──────────────────────────────────────────────────────────────
data class FpsSession(
    val packageName: String,
    val appLabel: String,
    val date: String,
    val avgFps: Float,
    val powerW: Float,
    val duration: Long,
    val icon: Drawable?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsStatsScreen(navController: NavController) {
    val context = LocalContext.current
    val pm = context.packageManager
    val deviceInfo = remember { TweakExecutor.getDeviceInfo(context) }
    val platform = remember { deviceInfo["soc"] ?: android.os.Build.HARDWARE.uppercase() }
    val model = remember { android.os.Build.MODEL }
    val sdk = remember { "SDK ${android.os.Build.VERSION.SDK_INT}" }

    var sessions by remember { mutableStateOf<List<FpsSession>>(emptyList()) }
    var showLiveMonitor by remember { mutableStateOf(false) }
    var targetPackage by remember { mutableStateOf("") }

    val executor = remember { TweakShellExecutor(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FPS Stats", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
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
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(4.dp))

            DeviceInfoCard(platform = platform, model = model, sdk = sdk)

            Spacer(Modifier.height(12.dp))

            if (showLiveMonitor) {
                LiveMonitorCard(
                    targetPackage = targetPackage,
                    executor = executor,
                    onStop = {
                        showLiveMonitor = false
                        targetPackage = ""
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            if (sessions.isEmpty() && !showLiveMonitor) {
                EmptySessionsCard()
            } else if (sessions.isNotEmpty()) {
                Text(
                    "Recorded Sessions",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                sessions.forEach { session ->
                    SessionCard(session = session, onDelete = {
                        sessions = sessions.filter { it !== session }
                    })
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(80.dp))
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.BottomCenter) {
            BottomBar(
                sessions = sessions,
                onClearAll = { sessions = emptyList() },
                onRecord = {
                    showLiveMonitor = !showLiveMonitor
                    if (showLiveMonitor) targetPackage = "com.example.game"
                }
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Live monitor card with full stats + charts
// ──────────────────────────────────────────────────────────────
@Composable
private fun LiveMonitorCard(
    targetPackage: String,
    executor: ShellExecutor,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf(FpsUiState()) }

    val monitor = remember { FpsMonitor(executor) }

    LaunchedEffect(targetPackage) {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        val refreshRate = wm.defaultDisplay.refreshRate.takeIf { it > 0f } ?: 60f
        while (true) {
            uiState = monitor.poll(targetPackage, refreshRate)
            delay(500L)
        }
    }

    val fps = uiState.fps
    val sys = uiState.system

    val fpsColor = when {
        fps.currentFps >= uiState.refreshRateHz * 0.9f -> Color(0xFF4CAF50)
        fps.currentFps >= uiState.refreshRateHz * 0.5f -> Color(0xFFFFCA28)
        fps.currentFps > 0f                             -> Color(0xFFEF5350)
        else                                            -> Color(0xFF78909C)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Live Monitor", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (fps.currentFps > 0f) "%.0f".format(fps.currentFps) else "--",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = fpsColor
                        )
                        Text("FPS", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fpsColor,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BackendBadge(backend = uiState.activeBackend)
                    Text(
                        "${uiState.refreshRateHz.toInt()}Hz",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SummaryGrid(fps = fps, sys = sys)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text("Performance Charts", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)

            if (uiState.fpsHistory.size >= 2) {
                RealtimeLineChart(
                    data = uiState.fpsHistory,
                    label = "FPS",
                    color = fpsColor,
                    maxValue = uiState.refreshRateHz * 1.1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.frameTimeHistory.size >= 2) {
                RealtimeLineChart(
                    data = uiState.frameTimeHistory,
                    label = "Frame Time",
                    color = Color(0xFF7B68EE),
                    unit = "ms",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.cpuHistory.size >= 2) {
                RealtimeLineChart(
                    data = uiState.cpuHistory,
                    label = "CPU Usage",
                    color = Color(0xFF4FC3F7),
                    maxValue = 100f,
                    unit = "%",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.tempHistory.size >= 2) {
                RealtimeLineChart(
                    data = uiState.tempHistory,
                    label = "Temperature",
                    color = Color(0xFFFF7043),
                    unit = "°C",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Summary grid (Scene-style stat cards)
// ──────────────────────────────────────────────────────────────
@Composable
private fun SummaryGrid(fps: FpsStats, sys: SystemStats) {
    val items = remember(fps, sys) {
        listOf(
            Triple("AVG FPS",     "%.1f".format(fps.avgFps),          Color(0xFF4CAF50)),
            Triple("MIN FPS",     "%.1f".format(fps.minFps),          Color(0xFFFFCA28)),
            Triple("MAX FPS",     "%.1f".format(fps.maxFps),          Color(0xFF4FC3F7)),
            Triple("1% LOW",      "%.1f".format(fps.fps1Low),         Color(0xFFEF5350)),
            Triple("5% LOW",      "%.1f".format(fps.fps5Low),         Color(0xFFFF7043)),
            Triple("FRAME TIME",  "%.1f ms".format(fps.frameTimeMs),  Color(0xFF7B68EE)),
            Triple("JANK",        "${fps.jankCount}",                 Color(0xFFFFCA28)),
            Triple("BIG JANK",    "${fps.bigJankCount}",              Color(0xFFEF5350)),
            Triple("SMOOTH",      "%.0f%%".format(fps.smoothness),    Color(0xFF4CAF50)),
            Triple("VARIANCE",    "%.1f".format(fps.variance),        Color(0xFF78909C)),
            Triple("CPU FREQ",    "${sys.cpuFreqMhz} MHz",            Color(0xFF4FC3F7)),
            Triple("TEMP",        if (sys.batteryTempC > 0f) "%.0f°C".format(sys.batteryTempC) else "--",
                                                                       Color(0xFFFF7043))
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (label, value, color) ->
                    StatCell(label = label, value = value, color = color, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.07f),
        border = BorderStroke(0.6.dp, color.copy(0.25f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.4.sp, textAlign = TextAlign.Center)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Backend badge
// ──────────────────────────────────────────────────────────────
@Composable
private fun BackendBadge(backend: FpsBackend) {
    val (label, color) = when (backend) {
        FpsBackend.SURFACEFLINGER_LATENCY   -> "SF Latency"  to Color(0xFF4CAF50)
        FpsBackend.GFXINFO_FRAMESTATS       -> "GFX Frames"  to Color(0xFF4FC3F7)
        FpsBackend.SURFACEFLINGER_FALLBACK  -> "SF Fallback" to Color(0xFFFFCA28)
        FpsBackend.NONE                     -> "Detecting…"  to Color(0xFF78909C)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(0.12f),
        border = BorderStroke(0.6.dp, color.copy(0.4f))
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Reused composables from original file
// ──────────────────────────────────────────────────────────────
@Composable
private fun DeviceInfoCard(platform: String, model: String, sdk: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceInfoColumn(icon = Icons.Default.Memory,       label = "Platform", value = platform, tint = Color(0xFF7B68EE))
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(icon = Icons.Default.PhoneAndroid, label = "Model",    value = model.take(12), tint = Color(0xFF4FC3F7))
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(icon = Icons.Default.Android,      label = "OS",       value = sdk, tint = Color(0xFF81C784))
        }
    }
}

@Composable
private fun DeviceInfoColumn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
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
private fun EmptySessionsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp))
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Speed, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f),
                modifier = Modifier.size(40.dp))
            Text("No sessions recorded yet", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
            Text("Tap Record to start tracking FPS", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.35f))
        }
    }
}

@Composable
private fun SessionCard(session: FpsSession, onDelete: () -> Unit) {
    val iconBitmap = remember(session.packageName) { session.icon?.toBitmap()?.asImageBitmap() }
    val fpsColor = when {
        session.avgFps >= 55f -> Color(0xFF4CAF50)
        session.avgFps >= 30f -> Color(0xFFFFCA28)
        else                  -> Color(0xFF78909C)
    }
    val duration = remember(session.duration) {
        val s = session.duration / 1000
        if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(session.appLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.avgFps > 0f)
                        Text("%.1f FPS".format(session.avgFps), fontSize = 11.sp, color = fpsColor,
                            fontWeight = FontWeight.SemiBold)
                    if (session.powerW > 0f)
                        Text("%.1fW".format(session.powerW), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                if (session.duration > 0L) duration else "--",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BottomBar(
    sessions: List<FpsSession>,
    onClearAll: () -> Unit,
    onRecord: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipItem(icon = Icons.Default.Android, label = "All") {}
                sessions.map { it.appLabel }.distinct().take(2).forEach { lbl ->
                    FilterChipItem(icon = Icons.Default.AllInclusive, label = lbl) {}
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onClearAll,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).size(40.dp)
                ) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onRecord,
                    modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).size(40.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun FilterChipItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
