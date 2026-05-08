package com.javapro.ui.screens

import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.animation.*
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
import androidx.compose.ui.draw.drawBehind
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

// ─── Theme-aware background colors ───────────────────────────────
private data class FpsBgColors(
    val deep   : Color,
    val card   : Color,
    val surface: Color,
    val glass  : Color
)

@Composable
private fun rememberFpsBgColors(): FpsBgColors {
    val scheme = MaterialTheme.colorScheme
    return FpsBgColors(
        deep    = scheme.background,
        card    = scheme.surfaceContainer,
        surface = scheme.surfaceContainerHigh,
        glass   = scheme.surfaceContainerHighest
    )
}

// ─── Color palette ───────────────────────────────────────────────

private val clrCyan     = Color(0xFF67C4D8)   // FPS
private val clrBlue     = Color(0xFF7EB8F7)   // CPU
private val clrPurple   = Color(0xFFB39DDB)   // GPU
private val clrOrange   = Color(0xFFFFB74D)   // Temp / warn
private val clrGreen    = Color(0xFF81C784)   // Good / smooth
private val clrRed      = Color(0xFFEF9A9A)   // Bad
private val clrNeutral  = Color(0xFF78909C)
private val clrFt       = Color(0xFFA5D6A7)

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
    val bg        = rememberFpsBgColors()
    val isPremium = remember { com.javapro.utils.PremiumManager.isPremium(context) }

    val deviceInfo = remember { TweakExecutor.getDeviceInfo(context) }
    val platform: String = remember { (deviceInfo["soc"] ?: android.os.Build.HARDWARE.uppercase()) as String }
    val model: String    = remember { android.os.Build.MODEL }
    val sdk: String      = remember { "Android ${android.os.Build.VERSION.RELEASE}" }
    val refreshRate: String = remember {
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val hz = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            wm.currentWindowMetrics.let { 0 }
        else 0
        "${(context.display?.refreshRate?.toInt() ?: 60)}Hz"
    }

    val factory = remember { FpsStatsViewModelFactory(context) }
    val vm: FpsStatsViewModel = viewModel(factory = factory)
    val uiState by vm.uiState.collectAsState()

    var sessions        by remember { mutableStateOf<List<FpsSession>>(emptyList()) }
    var showDebug       by remember { mutableStateOf(false) }
    var showPkgDialog   by remember { mutableStateOf(false) }
    var pkgInput        by remember { mutableStateOf("") }
    val canOverlay      = remember { Settings.canDrawOverlays(context) }

    DisposableEffect(Unit) {
        onDispose { vm.stopMonitoring() }
    }

    if (showPkgDialog) {
        PackageInputDialog(bg = bg, 
            current   = pkgInput,
            onConfirm = { pkg ->
                pkgInput      = pkg
                showPkgDialog = false
                if (pkg.isNotBlank()) vm.startMonitoring(pkg)
            },
            onDismiss = { showPkgDialog = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg.deep)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "FPS Stats",
                            fontWeight = FontWeight.Medium,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            vm.stopMonitoring()
                            navController.popBackStack()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(0.75f)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDebug = !showDebug }) {
                            Icon(
                                Icons.Default.BugReport,
                                null,
                                tint = if (showDebug) clrCyan else MaterialTheme.colorScheme.onSurface.copy(0.38f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bg.deep
                    )
                )
            },
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(4.dp))

                DeviceInfoChips(bg = bg, 
                    platform = platform,
                    model    = model,
                    sdk      = sdk,
                    hz       = refreshRate
                )

                Spacer(Modifier.height(14.dp))

                if (!canOverlay) {
                    OverlayPermissionBanner(bg = bg) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (uiState.isMonitoring) {
                    HeroFpsCard(bg = bg, uiState = uiState)
                    Spacer(Modifier.height(12.dp))

                    if (uiState.fpsHistory.size >= 2) {
                        ChartsSection(bg = bg, uiState = uiState)
                        Spacer(Modifier.height(12.dp))
                    }

                    if (showDebug) {
                        DebugPanel(bg = bg, dbg = uiState.debug, sys = uiState.system)
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (!uiState.isMonitoring && sessions.isEmpty()) {
                    EmptySessionsCard(bg = bg, )
                } else if (sessions.isNotEmpty()) {
                    Text(
                        "RECORDED SESSIONS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.35f),
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    sessions.forEach { session ->
                        SessionCard(bg = bg, session = session, onDelete = {
                            sessions = sessions.filter { it !== session }
                        })
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(96.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.BottomCenter
            ) {
                FloatingControlBar(bg = bg, isPremium = isPremium,
                    isMonitoring = uiState.isMonitoring,
                    currentPkg   = uiState.targetPackage,
                    sessions     = sessions,
                    onClearAll   = { sessions = emptyList() },
                    onRecord     = {
                        if (uiState.isMonitoring) vm.stopMonitoring()
                        else showPkgDialog = true
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
}

// ─── Package input dialog (unchanged logic) ──────────────────────
@Composable
private fun PackageInputDialog(bg: FpsBgColors, 
    current  : String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = bg.card,
        title = { Text("Target Package", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Masukkan package name aplikasi yang ingin dimonitor FPS-nya.",
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(0.55f)
                )
                OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    placeholder   = { Text("com.example.game", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.3f)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = clrCyan,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(0.15f),
                        focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor   = MaterialTheme.colorScheme.onSurface.copy(0.8f),
                        cursorColor          = clrCyan
                    )
                )
                Text("Quick:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.35f))
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
                            color = clrCyan.copy(0.8f),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                colors  = ButtonDefaults.buttonColors(containerColor = clrCyan)
            ) {
                Text("Start Monitor", color = bg.deep, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
            }
        }
    )
}

// ─── Device info compact chips ────────────────────────────────────
@Composable
private fun DeviceInfoChips(bg: FpsBgColors, platform: String, model: String, sdk: String, hz: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        DeviceChip(bg = bg, icon = Icons.Default.Memory, label = platform)
        DeviceChip(bg = bg, icon = Icons.Default.PhoneAndroid, label = model.take(14))
        DeviceChip(bg = bg, icon = Icons.Default.Android, label = sdk)
        DeviceChip(bg = bg, icon = Icons.Default.Speed, label = hz)
    }
}

@Composable
private fun DeviceChip(bg: FpsBgColors, 
    icon : androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg.glass)
            .border(BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(0.08f)), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(0.45f), modifier = Modifier.size(13.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.72f), fontWeight = FontWeight.Medium)
    }
}

// ─── Hero FPS card ────────────────────────────────────────────────
@Composable
private fun HeroFpsCard(bg: FpsBgColors, uiState: FpsUiState) {
    val fps    = uiState.fps
    val sys    = uiState.system
    val clrFps = fpsColor(fps.currentFps, uiState.refreshRateHz)

    val animatedFps by animateFloatAsState(
        targetValue    = fps.currentFps,
        animationSpec  = spring(stiffness = Spring.StiffnessLow),
        label          = "fps_anim"
    )

    val fpsText = when {
        fps.currentFps > 0f -> "%.0f".format(animatedFps)
        uiState.debug.parsedFrameCount == 0
            && uiState.activeBackend != FpsBackend.NONE -> "..."
        else -> "--"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        bg.glass,
                        bg.card.copy(0.92f)
                    )
                )
            )
            .border(BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline.copy(0.07f)), RoundedCornerShape(24.dp))
    ) {
        // Subtle glow behind FPS number
        if (fps.currentFps > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 24.dp, start = 24.dp)
                    .size(120.dp)
                    .drawBehind {
                        drawCircle(
                            color  = clrFps.copy(alpha = 0.07f),
                            radius = size.minDimension
                        )
                    }
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            // App name row + backend badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    uiState.targetPackage.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                        .ifBlank { uiState.targetPackage },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                BackendBadge(bg = bg, uiState.activeBackend)
            }

            Spacer(Modifier.height(16.dp))

            // Big FPS number — center focus
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    fpsText,
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Bold,
                    color = clrFps,
                    lineHeight = 80.sp
                )
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(
                        "FPS",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = clrFps.copy(0.55f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Sub info row
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${uiState.refreshRateHz.toInt()}Hz",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                )
                if (fps.totalFrames > 0) {
                    Text("•", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.2f))
                    Text(
                        "${fps.totalFrames} Frames",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(0.06f))
            Spacer(Modifier.height(16.dp))

            // Quick stats grid — 2 columns
            QuickStatsGrid(bg = bg, fps = fps, sys = sys)
        }
    }
}

// ─── Quick stats 2-col grid ───────────────────────────────────────
@Composable
private fun QuickStatsGrid(bg: FpsBgColors, fps: FpsStats, sys: SystemStats) {
    fun fmtFps(v: Float) = if (v > 0f) "%.1f".format(v) else "--"
    fun fmtMs(v: Float)  = if (v > 0f) "%.1f ms".format(v) else "--"
    fun fmtMhz(v: Int)   = if (v > 0) "${v} MHz" else "--"
    fun fmtPct(v: Float) = if (v >= 0f) "%.0f%%".format(v) else "--"

    val items = listOf(
        StatItem("AVG FPS",    fmtFps(fps.avgFps),                                           clrCyan),
        StatItem("MIN FPS",    fmtFps(fps.minFps),                                           clrNeutral),
        StatItem("MAX FPS",    fmtFps(fps.maxFps),                                           clrCyan),
        StatItem("1% LOW",     fmtFps(fps.fps1Low),                                          clrRed),
        StatItem("5% LOW",     fmtFps(fps.fps5Low),                                          clrOrange),
        StatItem("FRAME TIME", fmtMs(fps.frameTimeMs),                                       clrFt),
        StatItem("JANK",       if (fps.totalFrames > 0) "${fps.jankCount}" else "--",        clrOrange),
        StatItem("BIG JANK",   if (fps.totalFrames > 0) "${fps.bigJankCount}" else "--",     clrRed),
        StatItem("CPU FREQ",   fmtMhz(sys.cpuFreqMhz),                                      clrBlue),
        StatItem("GPU FREQ",   fmtMhz(sys.gpuFreqMhz),                                      clrPurple),
        StatItem("GPU LOAD",   if (sys.gpuUsage >= 0f) fmtPct(sys.gpuUsage) else "--",      clrPurple),
        StatItem("TEMP",       if (sys.batteryTempC > 0f) "%.0f°C".format(sys.batteryTempC) else "--",
                               if (sys.batteryTempC > 50f) clrRed else clrOrange)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    StatCard(bg = bg, item = item, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class StatItem(val label: String, val value: String, val color: Color)

@Composable
private fun StatCard(bg: FpsBgColors, item: StatItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg.surface)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(0.06f)), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(0.38f),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp
        )
        Text(
            item.value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = item.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─── Charts section ───────────────────────────────────────────────
@Composable
private fun ChartsSection(bg: FpsBgColors, uiState: FpsUiState) {
    val fps = uiState.fps
    val sys = uiState.system
    val clrFps = fpsColor(fps.currentFps, uiState.refreshRateHz)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartCard(bg = bg, 
            label    = "FPS",
            value    = if (fps.currentFps > 0f) "%.0f FPS".format(fps.currentFps) else "--",
            color    = clrFps,
            data     = uiState.fpsHistory,
            maxValue = uiState.refreshRateHz * 1.1f
        )
        if (uiState.frameTimeHistory.size >= 2) {
            ChartCard(bg = bg, 
                label = "FRAME TIME",
                value = if (fps.frameTimeMs > 0f) "%.1f ms".format(fps.frameTimeMs) else "--",
                color = clrFt,
                data  = uiState.frameTimeHistory,
                unit  = "ms"
            )
        }
        if (uiState.cpuHistory.size >= 2) {
            ChartCard(bg = bg, 
                label    = "CPU",
                value    = if (sys.cpuFreqMhz > 0) "${sys.cpuFreqMhz} MHz" else "--",
                color    = clrBlue,
                data     = uiState.cpuHistory,
                maxValue = 100f,
                unit     = "%"
            )
        }
        if (uiState.gpuHistory.size >= 2) {
            val isLoad = sys.gpuUsage >= 0f
            ChartCard(bg = bg, 
                label    = if (isLoad) "GPU LOAD" else "GPU FREQ",
                value    = if (isLoad) "%.0f%%".format(sys.gpuUsage) else "${sys.gpuFreqMhz} MHz",
                color    = clrPurple,
                data     = uiState.gpuHistory,
                maxValue = if (isLoad) 100f else null,
                unit     = if (isLoad) "%" else "MHz"
            )
        }
        if (uiState.tempHistory.size >= 2) {
            ChartCard(bg = bg, 
                label = "TEMPERATURE",
                value = if (sys.batteryTempC > 0f) "%.0f°C".format(sys.batteryTempC) else "--",
                color = clrOrange,
                data  = uiState.tempHistory,
                unit  = "°C"
            )
        }
    }
}

@Composable
private fun ChartCard(bg: FpsBgColors, 
    label   : String,
    value   : String,
    color   : Color,
    data    : List<Float>,
    maxValue: Float? = null,
    unit    : String = ""
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg.card)
            .border(BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(0.06f)), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(0.38f),
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
            Text(
                value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(Modifier.height(10.dp))
        RealtimeLineChart(
            data     = data,
            label    = label,
            color    = color,
            maxValue = maxValue,
            unit     = unit,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─── Backend badge ────────────────────────────────────────────────
@Composable
private fun BackendBadge(bg: FpsBgColors, backend: FpsBackend) {
    val (label, color) = when (backend) {
        FpsBackend.GFXINFO_FRAMESTATS     -> "GFX Frames"  to clrGreen
        FpsBackend.GFXINFO_TOTALFRAMES    -> "GFX Total"   to clrGreen
        FpsBackend.GFXINFO_DRAW_PROCESS   -> "GFX Draw"    to clrOrange
        FpsBackend.SURFACEFLINGER_LATENCY -> "SF Latency"  to clrBlue
        FpsBackend.SYSFS_MEASURED_FPS     -> "sysfs fps"   to clrPurple
        FpsBackend.FPSGO                  -> "fpsgo"       to clrPurple
        FpsBackend.NONE                   -> "Detecting…"  to clrNeutral
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(0.12f))
            .border(BorderStroke(0.5.dp, color.copy(0.25f)), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
    }
}

// ─── Debug panel ─────────────────────────────────────────────────
@Composable
private fun DebugPanel(bg: FpsBgColors, dbg: DebugInfo, sys: SystemStats) {
    val context    = LocalContext.current
    val canOverlay = Settings.canDrawOverlays(context)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg.surface)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(0.06f)), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            "DEBUG",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = clrCyan,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(4.dp))
        DebugRow(bg = bg, key = "active_backend",  value = dbg.activeBackend.name)
        DebugRow(bg = bg, key = "fail_reason",     value = dbg.backendFailReason.ifEmpty { "ok" })
        DebugRow(bg = bg, key = "target_pkg",      value = dbg.targetPackage)
        DebugRow(bg = bg, key = "parsed_frames",   value = "${dbg.parsedFrameCount}")
        DebugRow(bg = bg, key = "calculated_fps",  value = "%.2f".format(dbg.calculatedFps))
        DebugRow(bg = bg, key = "overlay_perm",    value = if (canOverlay) "granted" else "DENIED")
        DebugRow(bg = bg, key = "overlay_status",  value = FpsService.overlayStatus)
        DebugRow(bg = bg, key = "gpu_freq_path",   value = dbg.gpuFreqPath)
        DebugRow(bg = bg, key = "gpu_load_path",   value = dbg.gpuLoadPath)
        DebugRow(bg = bg, key = "gpu_fail",        value = dbg.gpuFailReason.ifEmpty { "ok" })
        DebugRow(bg = bg, key = "gpu_freq",        value = if (sys.gpuFreqMhz > 0) "${sys.gpuFreqMhz}MHz" else "--")
        DebugRow(bg = bg, key = "gpu_load",        value = if (sys.gpuUsage >= 0f) "%.1f%%".format(sys.gpuUsage) else "--")
        DebugRow(bg = bg, key = "cpu_freq",        value = if (sys.cpuFreqMhz > 0) "${sys.cpuFreqMhz}MHz" else "--")
        DebugRow(bg = bg, key = "temp",            value = if (sys.batteryTempC > 0f) "%.1f°C".format(sys.batteryTempC) else "--")
        Spacer(Modifier.height(4.dp))
        Text("last_shell:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.3f))
        Text(
            dbg.lastShellOutput.ifEmpty { "(empty)" },
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(0.3f),
            lineHeight = 12.sp
        )
    }
}

@Composable
private fun DebugRow(bg: FpsBgColors, key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            key, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(0.3f),
            modifier = Modifier.width(110.dp)
        )
        Text(
            value, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(0.65f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ─── Empty state ──────────────────────────────────────────────────
@Composable
private fun EmptySessionsCard(bg: FpsBgColors, ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bg.card)
            .border(BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(0.06f)), RoundedCornerShape(24.dp))
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Speed, null,
                tint = MaterialTheme.colorScheme.onSurface.copy(0.12f),
                modifier = Modifier.size(44.dp)
            )
            Text(
                "Belum ada sesi terekam",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(0.3f)
            )
            Text(
                "Tap ▶ untuk mulai monitor FPS",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(0.2f)
            )
        }
    }
}

// ─── Session card ─────────────────────────────────────────────────
@Composable
private fun SessionCard(bg: FpsBgColors, session: FpsSession, onDelete: () -> Unit) {
    val iconBitmap = remember(session.packageName) { session.icon?.toBitmap()?.asImageBitmap() }
    val clr = fpsColor(session.avgFps, 60f)
    val dur = remember(session.duration) {
        val s = session.duration / 1000
        if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg.card)
            .border(BorderStroke(0.6.dp, MaterialTheme.colorScheme.outline.copy(0.06f)), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null)
            androidx.compose.foundation.Image(
                iconBitmap, null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            )
        else
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Android, null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(0.3f),
                    modifier = Modifier.size(22.dp))
            }

        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.appLabel,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.35f))
                if (session.avgFps > 0f)
                    Text(
                        "%.1f FPS".format(session.avgFps),
                        fontSize = 11.sp,
                        color = clr,
                        fontWeight = FontWeight.SemiBold
                    )
            }
        }
        Text(dur, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.3f), modifier = Modifier.padding(end = 6.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, null,
                tint = MaterialTheme.colorScheme.onSurface.copy(0.22f),
                modifier = Modifier.size(17.dp))
        }
    }
}

// ─── Floating control bar ─────────────────────────────────────────
@Composable
private fun FloatingControlBar(bg: FpsBgColors, isPremium: Boolean,
    isMonitoring: Boolean,
    currentPkg  : String,
    sessions    : List<FpsSession>,
    onClearAll  : () -> Unit,
    onRecord    : () -> Unit,
    onOverlay   : () -> Unit
) {
    val recordBg by animateColorAsState(
        targetValue   = if (isMonitoring) Color(0xFFEF5350) else clrCyan,
        animationSpec = tween(300),
        label         = "rec_bg"
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = if (isPremium) 16.dp else 66.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(bg.glass.copy(0.92f))
                .border(BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline.copy(0.09f)), RoundedCornerShape(50.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status
            Column(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = isMonitoring,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "status_label"
                ) { monitoring ->
                    if (monitoring) {
                        Column {
                            Text(
                                "Monitoring",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = clrGreen
                            )
                            Text(
                                currentPkg.substringAfterLast("."),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(0.35f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            "Idle",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.35f)
                        )
                    }
                }
            }

            // Overlay button
            AnimatedVisibility(visible = isMonitoring) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(0.07f))
                        .clickable(onClick = onOverlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PictureInPicture,
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.6f),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // Clear button
            AnimatedVisibility(visible = sessions.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(0.07f))
                        .clickable(onClick = onClearAll),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Delete,
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.5f),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // Record / Stop — main CTA
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(recordBg)
                    .clickable(onClick = onRecord),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = isMonitoring,
                    transitionSpec = { scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut() },
                    label = "rec_icon"
                ) { monitoring ->
                    Icon(
                        if (monitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                        null,
                        tint = if (monitoring) MaterialTheme.colorScheme.onSurface else bg.deep,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ─── Overlay permission banner ────────────────────────────────────
@Composable
private fun OverlayPermissionBanner(bg: FpsBgColors, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(clrOrange.copy(0.07f))
            .border(BorderStroke(0.6.dp, clrOrange.copy(0.22f)), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Default.Warning, null, tint = clrOrange.copy(0.8f), modifier = Modifier.size(16.dp))
        Text(
            "Overlay permission diperlukan untuk floating FPS",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(0.65f),
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Izinkan", fontSize = 12.sp, color = clrOrange, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────
private fun fpsColor(fps: Float, hz: Float) = when {
    fps >= hz * 0.9f -> clrGreen
    fps >= hz * 0.5f -> clrOrange
    fps > 0f         -> clrRed
    else             -> clrNeutral
}
