package com.javapro.ui.screens

import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.javapro.fps.*
import com.javapro.fps.ui.RealtimeLineChart
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.delay

// ─── Color palette — soft Material You, bukan neon ───────────
private val clrGood    = Color(0xFF81C784)   // hijau lembut
private val clrWarn    = Color(0xFFFFB74D)   // orange lembut
private val clrBad     = Color(0xFFE57373)   // merah lembut
private val clrNeutral = Color(0xFF90A4AE)   // abu-abu biru
private val clrCpu     = Color(0xFF80DEEA)   // cyan lembut
private val clrGpu     = Color(0xFFCE93D8)   // ungu lembut
private val clrTemp    = Color(0xFFFFCC80)   // amber lembut
private val clrFt      = Color(0xFFA5D6A7)   // hijau muda

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
    val context   = LocalContext.current
    val deviceInfo = remember { TweakExecutor.getDeviceInfo(context) }
    val platform: String = remember { (deviceInfo["soc"] ?: android.os.Build.HARDWARE.uppercase()) as String }
    val model: String    = remember { android.os.Build.MODEL }
    val sdk: String      = remember { "SDK ${android.os.Build.VERSION.SDK_INT}" }

    var sessions       by remember { mutableStateOf<List<FpsSession>>(emptyList()) }
    var showLiveMonitor by remember { mutableStateOf(false) }
    var showDebug      by remember { mutableStateOf(false) }
    var targetPackage  by remember { mutableStateOf("") }
    val executor       = remember { TweakShellExecutor(context) }
    val canOverlay     = remember { Settings.canDrawOverlays(context) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("FPS Stats", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { showDebug = !showDebug }) {
                        Icon(
                            Icons.Default.BugReport,
                            null,
                            tint = if (showDebug) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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

            // Overlay permission warning
            if (!canOverlay) {
                OverlayPermissionBanner {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (showLiveMonitor) {
                LiveMonitorCard(
                    targetPackage = targetPackage,
                    executor      = executor,
                    showDebug     = showDebug,
                    onStop        = {
                        showLiveMonitor = false
                        targetPackage   = ""
                    }
                )
                Spacer(Modifier.height(12.dp))
            }

            if (sessions.isEmpty() && !showLiveMonitor) {
                EmptySessionsCard()
            } else if (sessions.isNotEmpty()) {
                Text(
                    "Recorded Sessions",
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.Bold,
                    color         = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier      = Modifier.padding(start = 4.dp, bottom = 6.dp)
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

        Box(
            modifier         = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.BottomCenter
        ) {
            BottomBar(
                sessions   = sessions,
                onClearAll = { sessions = emptyList() },
                onRecord   = {
                    showLiveMonitor = !showLiveMonitor
                    if (showLiveMonitor) targetPackage = "com.example.game"
                }
            )
        }
    }
}

// ─── Overlay permission banner ────────────────────────────────
@Composable
private fun OverlayPermissionBanner(onClick: () -> Unit) {
    Surface(
        shape  = RoundedCornerShape(12.dp),
        color  = clrWarn.copy(0.08f),
        border = BorderStroke(0.7.dp, clrWarn.copy(0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Warning, null, tint = clrWarn, modifier = Modifier.size(18.dp))
            Text(
                "Overlay permission diperlukan untuk floating FPS",
                fontSize = 12.sp,
                color    = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClick) {
                Text("Izinkan", fontSize = 12.sp, color = clrWarn)
            }
        }
    }
}

// ─── Live monitor card ─────────────────────────────────────────
@Composable
private fun LiveMonitorCard(
    targetPackage: String,
    executor: ShellExecutor,
    showDebug: Boolean,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf(FpsUiState()) }
    val monitor = remember { FpsMonitor(executor) }

    LaunchedEffect(targetPackage) {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE)
                as android.view.WindowManager
        @Suppress("DEPRECATION")
        val refreshRate = wm.defaultDisplay.refreshRate.takeIf { it > 0f } ?: 60f
        while (true) {
            uiState = monitor.poll(targetPackage, refreshRate)
            delay(500L)
        }
    }

    val fps = uiState.fps
    val sys = uiState.system
    val dbg = uiState.debug

    val fpsColor = fpsColor(fps.currentFps, uiState.refreshRateHz)

    Surface(
        shape    = RoundedCornerShape(20.dp),
        color    = MaterialTheme.colorScheme.surface,
        border   = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier              = Modifier.padding(16.dp),
            verticalArrangement   = Arrangement.spacedBy(12.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Live Monitor",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment      = Alignment.Bottom,
                        horizontalArrangement  = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            if (fps.currentFps > 0f) "%.0f".format(fps.currentFps) else "--",
                            fontSize   = 42.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color      = fpsColor
                        )
                        Text(
                            "FPS",
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color      = fpsColor.copy(0.7f),
                            modifier   = Modifier.padding(bottom = 7.dp)
                        )
                    }
                }
                Column(
                    horizontalAlignment  = Alignment.End,
                    verticalArrangement  = Arrangement.spacedBy(6.dp)
                ) {
                    BackendBadge(backend = uiState.activeBackend)
                    Text(
                        "${uiState.refreshRateHz.toInt()}Hz",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick  = onStop,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop, null,
                            tint     = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SummaryGrid(fps = fps, sys = sys)

            // Charts
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Charts",
                fontSize      = 11.sp,
                fontWeight    = FontWeight.SemiBold,
                color         = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )

            if (uiState.fpsHistory.size >= 2) {
                RealtimeLineChart(
                    data      = uiState.fpsHistory,
                    label     = "FPS",
                    color     = fpsColor,
                    maxValue  = uiState.refreshRateHz * 1.1f,
                    modifier  = Modifier.fillMaxWidth()
                )
            }
            if (uiState.frameTimeHistory.size >= 2) {
                RealtimeLineChart(
                    data     = uiState.frameTimeHistory,
                    label    = "Frame Time",
                    color    = clrFt,
                    unit     = "ms",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.cpuHistory.size >= 2) {
                RealtimeLineChart(
                    data     = uiState.cpuHistory,
                    label    = "CPU",
                    color    = clrCpu,
                    maxValue = 100f,
                    unit     = "%",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.gpuHistory.size >= 2) {
                val isLoadChart = uiState.system.gpuUsage >= 0f
                RealtimeLineChart(
                    data     = uiState.gpuHistory,
                    label    = if (isLoadChart) "GPU Load" else "GPU Freq",
                    color    = clrGpu,
                    maxValue = if (isLoadChart) 100f else null,
                    unit     = if (isLoadChart) "%" else "MHz",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.tempHistory.size >= 2) {
                RealtimeLineChart(
                    data     = uiState.tempHistory,
                    label    = "Temp",
                    color    = clrTemp,
                    unit     = "°C",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Debug panel
            if (showDebug) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DebugPanel(dbg = dbg, sys = sys)
            }
        }
    }
}

// ─── Debug panel ──────────────────────────────────────────────
@Composable
private fun DebugPanel(dbg: DebugInfo, sys: SystemStats) {
    val context = LocalContext.current
    val canOverlay = Settings.canDrawOverlays(context)

    Surface(
        shape  = RoundedCornerShape(10.dp),
        color  = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier              = Modifier.padding(10.dp),
            verticalArrangement   = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                "DEBUG PANEL",
                fontSize      = 9.sp,
                fontWeight    = FontWeight.Bold,
                color         = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(2.dp))
            DebugRow("active_backend",   dbg.activeBackend.name)
            DebugRow("fail_reason",      dbg.backendFailReason.ifEmpty { "ok" })
            DebugRow("target_pkg",       dbg.targetPackage)
            DebugRow("parsed_frames",    "${dbg.parsedFrameCount}")
            DebugRow("calculated_fps",   "%.2f".format(dbg.calculatedFps))
            DebugRow("overlay_perm",     if (canOverlay) "granted" else "DENIED")
            DebugRow("overlay_status",   FpsService.overlayStatus)
            DebugRow("gpu_freq_path",    dbg.gpuFreqPath)
            DebugRow("gpu_load_path",    dbg.gpuLoadPath)
            DebugRow("gpu_fail",         dbg.gpuFailReason.ifEmpty { "ok" })
            DebugRow("gpu_freq",         if (sys.gpuFreqMhz > 0) "${sys.gpuFreqMhz} MHz" else "--")
            DebugRow("gpu_load",         if (sys.gpuUsage >= 0f) "%.1f%%".format(sys.gpuUsage) else "--")
            DebugRow("cpu_freq",         if (sys.cpuFreqMhz > 0) "${sys.cpuFreqMhz} MHz" else "--")
            DebugRow("battery_temp",     if (sys.batteryTempC > 0f) "%.1f°C".format(sys.batteryTempC) else "--")
            Spacer(Modifier.height(4.dp))
            Text(
                "last_shell:",
                fontSize = 9.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
            )
            Text(
                dbg.lastShellOutput.ifEmpty { "(empty)" },
                fontSize    = 8.sp,
                fontFamily  = FontFamily.Monospace,
                color       = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f),
                lineHeight  = 12.sp
            )
        }
    }
}

@Composable
private fun DebugRow(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            key,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f),
            modifier   = Modifier.width(110.dp)
        )
        Text(
            value,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            color      = MaterialTheme.colorScheme.onSurface,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis
        )
    }
}

// ─── Summary grid ─────────────────────────────────────────────
@Composable
private fun SummaryGrid(fps: FpsStats, sys: SystemStats) {
    val items = remember(fps, sys) {
        listOf(
            Triple("AVG",      "%.1f".format(fps.avgFps),         clrGood),
            Triple("MIN",      "%.1f".format(fps.minFps),         clrNeutral),
            Triple("MAX",      "%.1f".format(fps.maxFps),         clrCpu),
            Triple("1% LOW",   "%.1f".format(fps.fps1Low),        clrBad),
            Triple("5% LOW",   "%.1f".format(fps.fps5Low),        clrWarn),
            Triple("FT avg",   "%.1fms".format(fps.frameTimeMs),  clrFt),
            Triple("JANK",     "${fps.jankCount}",                 clrWarn),
            Triple("BIG JANK", "${fps.bigJankCount}",              clrBad),
            Triple("SMOOTH",   "%.0f%%".format(fps.smoothness),   clrGood),
            Triple("VAR",      "%.1f".format(fps.variance),       clrNeutral),
            Triple("CPU",      if (sys.cpuFreqMhz > 0) "${sys.cpuFreqMhz}MHz" else "--", clrCpu),
            Triple("GPU freq", if (sys.gpuFreqMhz > 0) "${sys.gpuFreqMhz}MHz" else "--", clrGpu),
            Triple("GPU load", if (sys.gpuUsage >= 0f) "%.0f%%".format(sys.gpuUsage) else "--", clrGpu),
            Triple("TEMP",     if (sys.batteryTempC > 0f) "%.0f°C".format(sys.batteryTempC) else "--",
                               if (sys.batteryTempC > 50f) clrBad else clrTemp),
            Triple("FRAMES",   "${fps.totalFrames}",               clrNeutral)
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(4).forEach { row ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (label, value, color) ->
                    StatCell(label = label, value = value, color = color, modifier = Modifier.weight(1f))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape    = RoundedCornerShape(10.dp),
        color    = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
        border   = BorderStroke(0.5.dp, color.copy(0.2f)),
        modifier = modifier
    ) {
        Column(
            modifier              = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment   = Alignment.CenterHorizontally,
            verticalArrangement   = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                fontSize      = 8.sp,
                color         = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f),
                letterSpacing = 0.3.sp,
                textAlign     = TextAlign.Center
            )
            Text(
                value,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color      = color,
                textAlign  = TextAlign.Center,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Backend badge ─────────────────────────────────────────────
@Composable
private fun BackendBadge(backend: FpsBackend) {
    val (label, color) = when (backend) {
        FpsBackend.GFXINFO_FRAMESTATS   -> "GFX Frames"   to clrGood
        FpsBackend.GFXINFO_TOTALFRAMES  -> "GFX Total"    to clrGood
        FpsBackend.GFXINFO_DRAW_PROCESS -> "GFX Draw"     to clrWarn
        FpsBackend.SURFACEFLINGER_LATENCY -> "SF Latency" to clrCpu
        FpsBackend.SYSFS_MEASURED_FPS   -> "sysfs fps"    to clrGpu
        FpsBackend.FPSGO                -> "fpsgo"        to clrGpu
        FpsBackend.NONE                 -> "Detecting…"   to clrNeutral
    }
    Surface(
        shape  = RoundedCornerShape(6.dp),
        color  = color.copy(0.1f),
        border = BorderStroke(0.5.dp, color.copy(0.3f))
    ) {
        Text(
            label,
            fontSize      = 9.sp,
            color         = color,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            modifier      = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

// ─── Helper ───────────────────────────────────────────────────
private fun fpsColor(fps: Float, refreshHz: Float): Color = when {
    fps >= refreshHz * 0.9f -> clrGood
    fps >= refreshHz * 0.5f -> clrWarn
    fps > 0f                -> clrBad
    else                    -> clrNeutral
}

// ─── Reused composables ───────────────────────────────────────
@Composable
private fun DeviceInfoCard(platform: String, model: String, sdk: String) {
    Surface(
        shape    = RoundedCornerShape(20.dp),
        color    = MaterialTheme.colorScheme.surface,
        border   = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            DeviceInfoColumn(icon = Icons.Default.Memory,       label = "Platform", value = platform)
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(icon = Icons.Default.PhoneAndroid, label = "Model",    value = model.take(12))
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(icon = Icons.Default.Android,      label = "OS",       value = sdk)
        }
    }
}

@Composable
private fun DeviceInfoColumn(
    icon : androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    val tint = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(0.08f))
                .border(BorderStroke(0.7.dp, tint.copy(0.2f)), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(
            value,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptySessionsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(20.dp)
            )
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Speed, null,
                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                "No sessions recorded yet",
                fontSize   = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)
            )
            Text(
                "Tap Record to start tracking FPS",
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f)
            )
        }
    }
}

@Composable
private fun SessionCard(session: FpsSession, onDelete: () -> Unit) {
    val iconBitmap = remember(session.packageName) { session.icon?.toBitmap()?.asImageBitmap() }
    val fpsColor   = fpsColor(session.avgFps, 60f)
    val duration   = remember(session.duration) {
        val s = session.duration / 1000
        if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = MaterialTheme.colorScheme.surface,
        border   = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Android, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.appLabel,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.avgFps > 0f)
                        Text("%.1f FPS".format(session.avgFps), fontSize = 11.sp, color = fpsColor,
                            fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                if (session.duration > 0L) duration else "--",
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete, null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    sessions  : List<FpsSession>,
    onClearAll: () -> Unit,
    onRecord  : () -> Unit
) {
    Surface(
        shape    = RoundedCornerShape(20.dp),
        color    = MaterialTheme.colorScheme.surface,
        border   = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipItem(icon = Icons.Default.Android, label = "All") {}
                sessions.map { it.appLabel }.distinct().take(2).forEach { lbl ->
                    FilterChipItem(icon = Icons.Default.AllInclusive, label = lbl) {}
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick  = onClearAll,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Delete, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick  = onRecord,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .size(40.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow, null,
                        tint     = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipItem(
    icon   : androidx.compose.ui.graphics.vector.ImageVector,
    label  : String,
    onClick: () -> Unit
) {
    Surface(
        shape    = CircleShape,
        color    = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
