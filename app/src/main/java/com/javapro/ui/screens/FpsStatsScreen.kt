package com.javapro.ui.screens

import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.javapro.fps.*
import com.javapro.fps.ui.RealtimeLineChart
import com.javapro.utils.TweakExecutor

// ─── Soft Material You palette ──────────────────────────────────
private val clrGood    = Color(0xFF81C784)
private val clrWarn    = Color(0xFFFFB74D)
private val clrBad     = Color(0xFFE57373)
private val clrNeutral = Color(0xFF90A4AE)
private val clrCpu     = Color(0xFF80DEEA)
private val clrGpu     = Color(0xFFCE93D8)
private val clrTemp    = Color(0xFFFFCC80)
private val clrFt      = Color(0xFFA5D6A7)

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

    val deviceInfo = remember { TweakExecutor.getDeviceInfo(context) }
    val platform: String = remember { (deviceInfo["soc"] ?: android.os.Build.HARDWARE.uppercase()) as String }
    val model: String    = remember { android.os.Build.MODEL }
    val sdk: String      = remember { "SDK ${android.os.Build.VERSION.SDK_INT}" }

    val factory = remember { FpsStatsViewModelFactory(context) }
    val vm: FpsStatsViewModel = viewModel(factory = factory)
    val uiState by vm.uiState.collectAsState()

    var sessions        by remember { mutableStateOf<List<FpsSession>>(emptyList()) }
    var showDebug       by remember { mutableStateOf(false) }
    var showPkgDialog   by remember { mutableStateOf(false) }
    var pkgInput        by remember { mutableStateOf("") }
    val canOverlay      = remember { Settings.canDrawOverlays(context) }

    // Bersihkan saat leave screen
    DisposableEffect(Unit) {
        onDispose { vm.stopMonitoring() }
    }

    if (showPkgDialog) {
        PackageInputDialog(
            current   = pkgInput,
            onConfirm = { pkg ->
                pkgInput      = pkg
                showPkgDialog = false
                if (pkg.isNotBlank()) {
                    vm.startMonitoring(pkg)
                }
            },
            onDismiss = { showPkgDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FPS Stats", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.stopMonitoring()
                        navController.popBackStack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = { showDebug = !showDebug }) {
                        Icon(
                            Icons.Default.BugReport, null,
                            tint = if (showDebug) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

            // Live monitor — tampil jika sedang monitoring
            if (uiState.isMonitoring) {
                LiveMonitorCard(
                    uiState   = uiState,
                    showDebug = showDebug,
                    onStop    = { vm.stopMonitoring() }
                )
                Spacer(Modifier.height(12.dp))
            }

            if (!uiState.isMonitoring && sessions.isEmpty()) {
                EmptySessionsCard()
            } else if (sessions.isNotEmpty()) {
                Text(
                    "Recorded Sessions",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp,
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
                isMonitoring = uiState.isMonitoring,
                currentPkg   = uiState.targetPackage,
                sessions     = sessions,
                onClearAll   = { sessions = emptyList() },
                onRecord     = {
                    if (uiState.isMonitoring) {
                        vm.stopMonitoring()
                    } else {
                        showPkgDialog = true
                    }
                },
                onOverlay = {
                    if (canOverlay && uiState.targetPackage.isNotBlank()) {
                        val i = Intent(context, FpsService::class.java).apply {
                            putExtra(FpsService.EXTRA_PACKAGE, uiState.targetPackage)
                            putExtra(FpsService.EXTRA_SHOW_OVERLAY, true)
                        }
                        context.startForegroundService(i)
                    }
                }
            )
        }
    }
}

// ─── Package input dialog ────────────────────────────────────────
@Composable
private fun PackageInputDialog(
    current  : String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target Package", fontWeight = FontWeight.Bold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Masukkan package name aplikasi yang ingin dimonitor FPS-nya.",
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    placeholder   = { Text("com.example.game", fontSize = 13.sp) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                // Quick picks
                Text("Quick:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    "com.miHoYo.GenshinImpact",
                    "com.pubg.imobile",
                    "com.mobile.legends",
                    "com.riotgames.league.wildrift"
                ).forEach { pkg ->
                    TextButton(
                        onClick  = { text = pkg },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(pkg, fontSize = 11.sp, textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }) {
                Text("Start Monitor")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

// ─── Live monitor card ────────────────────────────────────────────
@Composable
private fun LiveMonitorCard(
    uiState  : FpsUiState,
    showDebug: Boolean,
    onStop   : () -> Unit
) {
    val fps  = uiState.fps
    val sys  = uiState.system
    val dbg  = uiState.debug
    val clrFps = fpsColor(fps.currentFps, uiState.refreshRateHz)

    // Tampilkan "--" jika belum ada data sama sekali
    val fpsText = when {
        fps.currentFps > 0f             -> "%.0f".format(fps.currentFps)
        uiState.debug.parsedFrameCount == 0
            && uiState.activeBackend != FpsBackend.NONE -> "..."
        else                            -> "--"
    }

    Surface(
        shape    = RoundedCornerShape(20.dp),
        color    = MaterialTheme.colorScheme.surface,
        border   = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        uiState.targetPackage,
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.55f)
                    )
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            fpsText,
                            fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = clrFps
                        )
                        Text(
                            "FPS", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = clrFps.copy(0.7f), modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackendBadge(uiState.activeBackend)
                    Text("${uiState.refreshRateHz.toInt()}Hz", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(
                        onClick  = onStop,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer).size(36.dp)
                    ) {
                        Icon(Icons.Default.Stop, null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SummaryGrid(fps = fps, sys = sys)

            // Charts — hanya jika sudah ada data
            if (uiState.fpsHistory.size >= 2) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Charts", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)

                RealtimeLineChart(
                    data = uiState.fpsHistory, label = "FPS", color = clrFps,
                    maxValue = uiState.refreshRateHz * 1.1f, modifier = Modifier.fillMaxWidth()
                )
                if (uiState.frameTimeHistory.size >= 2)
                    RealtimeLineChart(
                        data = uiState.frameTimeHistory, label = "Frame Time",
                        color = clrFt, unit = "ms", modifier = Modifier.fillMaxWidth()
                    )
                if (uiState.cpuHistory.size >= 2)
                    RealtimeLineChart(
                        data = uiState.cpuHistory, label = "CPU",
                        color = clrCpu, maxValue = 100f, unit = "%", modifier = Modifier.fillMaxWidth()
                    )
                if (uiState.gpuHistory.size >= 2) {
                    val isLoad = sys.gpuUsage >= 0f
                    RealtimeLineChart(
                        data = uiState.gpuHistory,
                        label    = if (isLoad) "GPU Load" else "GPU Freq",
                        color    = clrGpu,
                        maxValue = if (isLoad) 100f else null,
                        unit     = if (isLoad) "%" else "MHz",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (uiState.tempHistory.size >= 2)
                    RealtimeLineChart(
                        data = uiState.tempHistory, label = "Temp",
                        color = clrTemp, unit = "°C", modifier = Modifier.fillMaxWidth()
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

// ─── Summary grid ────────────────────────────────────────────────
@Composable
private fun SummaryGrid(fps: FpsStats, sys: SystemStats) {
    fun fmtFps(v: Float) = if (v > 0f) "%.1f".format(v) else "--"
    fun fmtMs(v: Float)  = if (v > 0f) "%.1fms".format(v) else "--"
    fun fmtMhz(v: Int)   = if (v > 0) "${v}MHz" else "--"
    fun fmtPct(v: Float) = if (v >= 0f) "%.0f%%".format(v) else "--"

    val items = listOf(
        Triple("AVG",      fmtFps(fps.avgFps),             clrGood),
        Triple("MIN",      fmtFps(fps.minFps),             clrNeutral),
        Triple("MAX",      fmtFps(fps.maxFps),             clrCpu),
        Triple("1% LOW",   fmtFps(fps.fps1Low),            clrBad),
        Triple("5% LOW",   fmtFps(fps.fps5Low),            clrWarn),
        Triple("FT",       fmtMs(fps.frameTimeMs),         clrFt),
        Triple("JANK",     if (fps.totalFrames > 0) "${fps.jankCount}" else "--", clrWarn),
        Triple("B.JANK",   if (fps.totalFrames > 0) "${fps.bigJankCount}" else "--", clrBad),
        Triple("SMOOTH",   if (fps.totalFrames > 0) fmtPct(fps.smoothness) else "--", clrGood),
        Triple("VAR",      if (fps.totalFrames > 0) "%.1f".format(fps.variance) else "--", clrNeutral),
        Triple("CPU",      fmtMhz(sys.cpuFreqMhz),        clrCpu),
        Triple("GPU freq", fmtMhz(sys.gpuFreqMhz),        clrGpu),
        Triple("GPU load", if (sys.gpuUsage >= 0f) fmtPct(sys.gpuUsage) else "--", clrGpu),
        Triple("TEMP",     if (sys.batteryTempC > 0f) "%.0f°C".format(sys.batteryTempC) else "--",
                           if (sys.batteryTempC > 50f) clrBad else clrTemp),
        Triple("FRAMES",   if (fps.totalFrames > 0) "${fps.totalFrames}" else "--", clrNeutral)
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, value, color) ->
                    StatCell(label, value, color, Modifier.weight(1f))
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
        color    = MaterialTheme.colorScheme.surfaceVariant.copy(0.45f),
        border   = BorderStroke(0.5.dp, color.copy(0.18f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.55f),
                letterSpacing = 0.3.sp, textAlign = TextAlign.Center)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─── Debug panel ─────────────────────────────────────────────────
@Composable
private fun DebugPanel(dbg: DebugInfo, sys: SystemStats) {
    val context = LocalContext.current
    val canOverlay = Settings.canDrawOverlays(context)

    Surface(
        shape  = RoundedCornerShape(10.dp),
        color  = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("DEBUG", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(Modifier.height(2.dp))
            DebugRow("active_backend",  dbg.activeBackend.name)
            DebugRow("fail_reason",     dbg.backendFailReason.ifEmpty { "ok" })
            DebugRow("target_pkg",      dbg.targetPackage)
            DebugRow("parsed_frames",   "${dbg.parsedFrameCount}")
            DebugRow("calculated_fps",  "%.2f".format(dbg.calculatedFps))
            DebugRow("overlay_perm",    if (canOverlay) "granted" else "DENIED")
            DebugRow("overlay_status",  FpsService.overlayStatus)
            DebugRow("gpu_freq_path",   dbg.gpuFreqPath)
            DebugRow("gpu_load_path",   dbg.gpuLoadPath)
            DebugRow("gpu_fail",        dbg.gpuFailReason.ifEmpty { "ok" })
            DebugRow("gpu_freq",        if (sys.gpuFreqMhz > 0) "${sys.gpuFreqMhz}MHz" else "--")
            DebugRow("gpu_load",        if (sys.gpuUsage >= 0f) "%.1f%%".format(sys.gpuUsage) else "--")
            DebugRow("cpu_freq",        if (sys.cpuFreqMhz > 0) "${sys.cpuFreqMhz}MHz" else "--")
            DebugRow("temp",            if (sys.batteryTempC > 0f) "%.1f°C".format(sys.batteryTempC) else "--")
            Spacer(Modifier.height(4.dp))
            Text("last_shell:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f))
            Text(dbg.lastShellOutput.ifEmpty { "(empty)" },
                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.45f), lineHeight = 12.sp)
        }
    }
}

@Composable
private fun DebugRow(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(key, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.45f),
            modifier = Modifier.width(110.dp))
        Text(value, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── Backend badge ────────────────────────────────────────────────
@Composable
private fun BackendBadge(backend: FpsBackend) {
    val (label, color) = when (backend) {
        FpsBackend.GFXINFO_FRAMESTATS    -> "GFX Frames"   to clrGood
        FpsBackend.GFXINFO_TOTALFRAMES   -> "GFX Total"    to clrGood
        FpsBackend.GFXINFO_DRAW_PROCESS  -> "GFX Draw"     to clrWarn
        FpsBackend.SURFACEFLINGER_LATENCY -> "SF Latency"  to clrCpu
        FpsBackend.SYSFS_MEASURED_FPS    -> "sysfs fps"    to clrGpu
        FpsBackend.FPSGO                 -> "fpsgo"        to clrGpu
        FpsBackend.NONE                  -> "Detecting…"   to clrNeutral
    }
    Surface(
        shape  = RoundedCornerShape(6.dp),
        color  = color.copy(0.1f),
        border = BorderStroke(0.5.dp, color.copy(0.28f))
    ) {
        Text(label, fontSize = 9.sp, color = color, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

// ─── Helpers ─────────────────────────────────────────────────────
private fun fpsColor(fps: Float, hz: Float) = when {
    fps >= hz * 0.9f -> clrGood
    fps >= hz * 0.5f -> clrWarn
    fps > 0f         -> clrBad
    else             -> clrNeutral
}

// ─── Reusable composables ─────────────────────────────────────────
@Composable
private fun OverlayPermissionBanner(onClick: () -> Unit) {
    Surface(
        shape  = RoundedCornerShape(12.dp),
        color  = clrWarn.copy(0.07f),
        border = BorderStroke(0.7.dp, clrWarn.copy(0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Warning, null, tint = clrWarn, modifier = Modifier.size(18.dp))
            Text("Overlay permission diperlukan untuk floating FPS",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text("Izinkan", fontSize = 12.sp, color = clrWarn) }
        }
    }
}

@Composable
private fun DeviceInfoCard(platform: String, model: String, sdk: String) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceInfoColumn(Icons.Default.Memory, "Platform", platform)
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(Icons.Default.PhoneAndroid, "Model", model.take(12))
            VerticalDivider(modifier = Modifier.height(48.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DeviceInfoColumn(Icons.Default.Android, "OS", sdk)
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(tint.copy(0.08f))
                .border(BorderStroke(0.7.dp, tint.copy(0.2f)), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)) }
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun EmptySessionsCard() {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(20.dp))
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Speed, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.28f),
                modifier = Modifier.size(40.dp))
            Text("Belum ada sesi terekam", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.45f))
            Text("Tap ▶ untuk mulai monitor FPS", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f))
        }
    }
}

@Composable
private fun SessionCard(session: FpsSession, onDelete: () -> Unit) {
    val iconBitmap = remember(session.packageName) { session.icon?.toBitmap()?.asImageBitmap() }
    val clr = fpsColor(session.avgFps, 60f)
    val dur = remember(session.duration) {
        val s = session.duration / 1000
        if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }
    Surface(
        shape  = RoundedCornerShape(16.dp),
        color  = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (iconBitmap != null)
                androidx.compose.foundation.Image(iconBitmap, null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            else
                Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Android, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp))
                }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(session.appLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.date, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (session.avgFps > 0f)
                        Text("%.1f FPS".format(session.avgFps), fontSize = 11.sp, color = clr,
                            fontWeight = FontWeight.SemiBold)
                }
            }
            Text(dur, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.38f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BottomBar(
    isMonitoring: Boolean,
    currentPkg  : String,
    sessions    : List<FpsSession>,
    onClearAll  : () -> Unit,
    onRecord    : () -> Unit,
    onOverlay   : () -> Unit
) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        color  = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status text
            Column(modifier = Modifier.weight(1f)) {
                if (isMonitoring) {
                    Text("Monitoring", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = clrGood)
                    Text(currentPkg, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text("Idle", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sessions.isNotEmpty()) {
                    IconButton(onClick = onClearAll,
                        modifier = Modifier.clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant).size(40.dp)) {
                        Icon(Icons.Default.Delete, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                    }
                }

                // Overlay button — hanya jika sedang monitoring
                if (isMonitoring) {
                    IconButton(onClick = onOverlay,
                        modifier = Modifier.clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer).size(40.dp)) {
                        Icon(Icons.Default.PictureInPicture, null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp))
                    }
                }

                // Record / Stop button
                IconButton(onClick = onRecord,
                    modifier = Modifier.clip(CircleShape)
                        .background(
                            if (isMonitoring) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primary
                        ).size(40.dp)) {
                    Icon(
                        if (isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null,
                        tint = if (isMonitoring) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
