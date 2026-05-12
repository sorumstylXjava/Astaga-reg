package com.javapro.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.javapro.utils.PreferenceManager
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private object GpuBackendCache {
    var freqPath: String? = null
    var loadPath: String? = null
    val invalidFreqPaths = mutableSetOf<String>()
    val invalidLoadPaths = mutableSetOf<String>()
    var detectedName: String = "—"
    var maxFreqMhz: Int = 0
}

private val GPU_FREQ_PATHS = listOf(
    "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"  to "hz",
    "/sys/class/kgsl/kgsl-3d0/gpuclk"            to "hz",
    "/sys/class/kgsl/kgsl-3d0/clock_mhz"         to "mhz",
    "/sys/class/devfreq/gpufreq/cur_freq"         to "hz",
    "/sys/class/devfreq/mtk-gpufreq/cur_freq"     to "hz",
    "/sys/kernel/gpu/gpu_clock"                   to "mhz",
    "/proc/gpufreq/gpufreq_var_dump"              to "dump",
    "/proc/gpufreqv2/fix_custom_freq_volt"        to "dump",
)

private val GPU_MAX_FREQ_PATHS = listOf(
    "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"  to "hz",
    "/sys/class/kgsl/kgsl-3d0/max_clock_mhz"     to "mhz",
    "/sys/class/devfreq/gpufreq/max_freq"         to "hz",
    "/sys/class/devfreq/mtk-gpufreq/max_freq"     to "hz",
    "/sys/kernel/gpu/gpu_max_clock"               to "mhz",
)

private val GPU_LOAD_PATHS = listOf(
    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
    "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
    "/sys/class/kgsl/kgsl-3d0/gpubusy",
    "/sys/class/kgsl/kgsl-3d0/gpuload",
    "/sys/module/ged/parameters/gpu_loading",
    "/sys/kernel/ged/hal/gpu_utilization",
    "/sys/kernel/debug/ged/hal/gpu_utilization",
    "/sys/kernel/gpu/gpu_busy",
    "/sys/class/devfreq/gpufreq/mali_ondemand/utilisation",
    "/sys/devices/platform/1c500000.mali/utilization",
)

private fun tryReadSysFile(path: String): String? {
    return try {
        val text = File(path).readText().trim()
        text.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        try {
            val out = TweakExecutor.executeWithOutputSync("cat $path")
            out.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}

private fun parseMhzFromRaw(raw: String, unit: String): Int {
    return when (unit) {
        "mhz"  -> raw.trim().toLongOrNull()?.toInt() ?: 0
        "hz"   -> ((raw.trim().toLongOrNull() ?: 0L) / 1_000_000L).toInt()
        "dump" -> {
            val nums = raw.lines()
                .flatMap { it.split("\\s+".toRegex()) }
                .mapNotNull { it.toLongOrNull() }
                .filter { it in 50_000L..3_000_000_000L }
            val v = nums.firstOrNull() ?: return 0
            when {
                v > 1_000_000L -> (v / 1_000_000L).toInt()
                v > 1_000L     -> (v / 1_000L).toInt()
                else           -> v.toInt()
            }
        }
        else -> 0
    }
}

private fun scanMaliDevfreqFreqPath(): String? {
    val dir = File("/sys/class/devfreq")
    if (!dir.exists()) return null
    return dir.listFiles()
        ?.filter { it.name.lowercase().run { contains("mali") || contains("gpu") || contains("kgsl") } }
        ?.firstOrNull()
        ?.let { File(it, "cur_freq").takeIf { f -> f.exists() }?.absolutePath }
}

private fun scanMaliPlatformFreqPath(): String? {
    val dir = File("/sys/devices/platform")
    if (!dir.exists()) return null
    return dir.listFiles()
        ?.filter { it.name.lowercase().run { contains("mali") || contains("gpu") } }
        ?.mapNotNull { dev ->
            dev.listFiles()
                ?.firstOrNull { it.name == "devfreq" }
                ?.listFiles()
                ?.firstOrNull()
                ?.let { File(it, "cur_freq").takeIf { f -> f.exists() }?.absolutePath }
        }
        ?.firstOrNull()
}

private fun scanMaliPlatformLoadPath(): String? {
    val dir = File("/sys/devices/platform")
    if (!dir.exists()) return null
    return dir.listFiles()
        ?.filter { it.name.lowercase().contains("mali") }
        ?.mapNotNull { dev ->
            File(dev, "utilization").takeIf { it.exists() }?.absolutePath
        }
        ?.firstOrNull()
}

private fun resolveGpuMaxMhz(): Int {
    for ((path, unit) in GPU_MAX_FREQ_PATHS) {
        val raw = tryReadSysFile(path) ?: continue
        val mhz = parseMhzFromRaw(raw, unit)
        if (mhz > 0) return mhz
    }
    return 0
}

fun resolveGpuFreqAndMax(): Pair<Int, Int> {
    val cached = GpuBackendCache.freqPath
    if (cached != null && cached !in GpuBackendCache.invalidFreqPaths) {
        val unit = GPU_FREQ_PATHS.firstOrNull { it.first == cached }?.second ?: "hz"
        val raw  = tryReadSysFile(cached)
        if (raw != null) {
            val mhz = parseMhzFromRaw(raw, unit)
            if (mhz > 0) return Pair(mhz, GpuBackendCache.maxFreqMhz)
        }
        GpuBackendCache.invalidFreqPaths.add(cached)
        GpuBackendCache.freqPath = null
    }

    for ((path, unit) in GPU_FREQ_PATHS) {
        if (path in GpuBackendCache.invalidFreqPaths) continue
        val raw = tryReadSysFile(path) ?: run { GpuBackendCache.invalidFreqPaths.add(path); continue }
        val mhz = parseMhzFromRaw(raw, unit)
        if (mhz > 0) {
            GpuBackendCache.freqPath = path
            GpuBackendCache.detectedName = when {
                path.contains("kgsl")                           -> "Adreno"
                path.contains("mtk") || path.contains("gpufreq") -> "Mali MTK"
                path.contains("mali")                           -> "Mali"
                else                                            -> "GPU"
            }
            val maxMhz = resolveGpuMaxMhz()
            GpuBackendCache.maxFreqMhz = maxMhz
            return Pair(mhz, maxMhz)
        }
        GpuBackendCache.invalidFreqPaths.add(path)
    }

    for (path in listOf(scanMaliDevfreqFreqPath(), scanMaliPlatformFreqPath()).filterNotNull()) {
        if (path in GpuBackendCache.invalidFreqPaths) continue
        val raw = tryReadSysFile(path) ?: continue
        val mhz = parseMhzFromRaw(raw, "hz")
        if (mhz > 0) {
            GpuBackendCache.freqPath = path
            GpuBackendCache.detectedName = "Mali"
            val maxMhz = resolveGpuMaxMhz()
            GpuBackendCache.maxFreqMhz = maxMhz
            return Pair(mhz, maxMhz)
        }
    }

    return Pair(0, GpuBackendCache.maxFreqMhz)
}

private fun parseGpuLoad(raw: String, path: String): Float {
    if (path.contains("gpubusy") && !path.contains("percentage")) {
        val parts = raw.split("\\s+".toRegex())
        val used  = parts.getOrNull(0)?.toLongOrNull() ?: return -1f
        val total = parts.getOrNull(1)?.toLongOrNull() ?: return -1f
        if (total <= 0) return -1f
        return (used.toFloat() / total * 100f).coerceIn(0f, 100f)
    }
    val num = Regex("\\d+(\\.\\d+)?").find(raw)?.value?.toFloatOrNull() ?: return -1f
    return num.coerceIn(0f, 100f)
}

fun resolveGpuLoad(): Float {
    val cached = GpuBackendCache.loadPath
    if (cached != null && cached !in GpuBackendCache.invalidLoadPaths) {
        val raw = tryReadSysFile(cached)
        if (raw != null) {
            val load = parseGpuLoad(raw, cached)
            if (load >= 0f) return load
        }
        GpuBackendCache.invalidLoadPaths.add(cached)
        GpuBackendCache.loadPath = null
    }

    for (path in GPU_LOAD_PATHS) {
        if (path in GpuBackendCache.invalidLoadPaths) continue
        val raw = tryReadSysFile(path) ?: run { GpuBackendCache.invalidLoadPaths.add(path); continue }
        val load = parseGpuLoad(raw, path)
        if (load >= 0f) {
            GpuBackendCache.loadPath = path
            return load
        }
        GpuBackendCache.invalidLoadPaths.add(path)
    }

    scanMaliPlatformLoadPath()?.let { path ->
        if (path !in GpuBackendCache.invalidLoadPaths) {
            val raw = tryReadSysFile(path)
            if (raw != null) {
                val load = parseGpuLoad(raw, path)
                if (load >= 0f) {
                    GpuBackendCache.loadPath = path
                    return load
                }
            }
        }
    }

    return -1f
}

data class CoreStatSnap(val idle: Long, val total: Long)

fun readPerCoreStatDirect(): List<CoreStatSnap> {
    return try {
        File("/proc/stat").readLines()
            .filter { it.matches(Regex("cpu[0-9]+.*")) }
            .map { line ->
                val parts = line.trim().split("\\s+".toRegex()).drop(1).map { it.toLongOrNull() ?: 0L }
                CoreStatSnap(
                    idle  = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L },
                    total = parts.sum()
                )
            }
    } catch (_: Exception) { emptyList() }
}

fun calcCoreUsage(prev: CoreStatSnap, cur: CoreStatSnap): Float {
    val dt = cur.total - prev.total
    val di = cur.idle  - prev.idle
    return if (dt <= 0) 0f else ((dt - di).toFloat() / dt * 100f).coerceIn(0f, 100f)
}

data class RamInfo(val totalMb: Long, val availMb: Long, val usedMb: Long, val cachedMb: Long)

fun readRamInfo(context: Context): RamInfo {
    val am  = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mem)
    val totalMb = mem.totalMem / 1024 / 1024
    val availMb = mem.availMem / 1024 / 1024
    val usedMb  = totalMb - availMb
    val cachedMb = try {
        File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("Cached:") }
            ?.split("\\s+".toRegex())
            ?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: 0L
    } catch (_: Exception) { 0L }
    return RamInfo(totalMb, availMb, usedMb, cachedMb)
}

data class StorageInfo(val totalGb: Float, val usedGb: Float, val freeGb: Float, val type: String)

private object StorageTypeCache {
    var type: String? = null
}

private fun detectStorageType(): String {
    StorageTypeCache.type?.let { return it }
    val detected = when {
        try { File("/proc/scsi/scsi").readText().contains("UFS", ignoreCase = true) } catch (_: Exception) { false } -> "UFS"
        File("/sys/block/mmcblk0").exists() -> "eMMC"
        File("/sys/block/sda").exists() -> {
            val vendor = try { File("/sys/block/sda/device/vendor").readText().trim() } catch (_: Exception) { "" }
            val model  = try { File("/sys/block/sda/device/model").readText().trim() } catch (_: Exception) { "" }
            if (vendor.isNotEmpty() || model.isNotEmpty()) "UFS" else "Unknown"
        }
        else -> "Unknown"
    }
    StorageTypeCache.type = detected
    return detected
}

fun readStorageInfo(): StorageInfo {
    return try {
        val extDir = android.os.Environment.getExternalStorageDirectory()
        val stat   = StatFs(extDir.absolutePath)
        val block  = stat.blockSizeLong
        val total  = stat.blockCountLong * block
        val free   = stat.availableBlocksLong * block
        val used   = total - free
        val toGb   = { b: Long -> b / 1024f / 1024f / 1024f }
        StorageInfo(toGb(total), toGb(used), toGb(free), detectStorageType())
    } catch (_: Exception) {
        StorageInfo(0f, 0f, 0f, "Unknown")
    }
}

private fun readCpuTempCelsius(): Float {
    val paths = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone2/temp",
        "/sys/devices/virtual/thermal/thermal_zone0/temp",
        "/sys/kernel/debug/tsens_dbg/tsens_dbg_0",
    )
    for (path in paths) {
        val raw = try { File(path).readText().trim().toLongOrNull() } catch (_: Exception) { null } ?: continue
        if (raw > 0) return (if (raw > 1000) raw / 1000f else raw.toFloat()).coerceIn(0f, 120f)
    }
    return -1f
}

private fun readBatteryTempCelsius(context: Context): Float {
    return try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp   = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (temp < 0) -1f else temp / 10f
    } catch (_: Exception) { -1f }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    navController : NavController,
    prefManager   : PreferenceManager
) {
    val context         = LocalContext.current
    val isRooted        = remember { TweakExecutor.checkRoot() }
    val isShizukuActive = remember { com.javapro.utils.ShizukuManager.isAvailable() }

    var cpuUsage    by remember { mutableStateOf(0f) }
    var cpuHistory  by remember { mutableStateOf(listOf<Float>()) }
    var cpuClusters by remember { mutableStateOf(CpuClusterCache.clusters) }
    var coreUsages  by remember { mutableStateOf(listOf<Float>()) }
    var cpuTempC    by remember { mutableStateOf(-1f) }
    var cpuFreqMhz  by remember { mutableStateOf(0) }
    var cpuGovernor by remember { mutableStateOf("") }

    var gpuLoad     by remember { mutableStateOf(-1f) }
    var gpuFreqMhz  by remember { mutableStateOf(0) }
    var gpuMaxMhz   by remember { mutableStateOf(0) }
    var gpuHistory  by remember { mutableStateOf(listOf<Float>()) }
    var gpuName     by remember { mutableStateOf("—") }

    var ramInfo     by remember { mutableStateOf(RamInfo(0, 0, 0, 0)) }
    var battTempC   by remember { mutableStateOf(-1f) }
    var storageInfo by remember { mutableStateOf(StorageInfo(0f, 0f, 0f, "—")) }

    LaunchedEffect(Unit) {
        var prevSnap: CpuStatSnapshot? = null
        var prevCores: List<CoreStatSnap> = emptyList()
        while (true) {
            withContext(Dispatchers.IO) {
                val curSnap  = readCpuStatSnapshot()
                val curCores = readPerCoreStatDirect()
                if (prevSnap != null) cpuUsage = calcCpuUsage(prevSnap!!, curSnap)
                if (prevCores.isNotEmpty() && curCores.size == prevCores.size) {
                    coreUsages = curCores.zip(prevCores).map { (cur, prev) -> calcCoreUsage(prev, cur) }
                }
                prevSnap  = curSnap
                prevCores = curCores
                cpuTempC  = readCpuTempCelsius()
            }
            cpuHistory = (cpuHistory + cpuUsage).takeLast(60)
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        if (com.javapro.utils.ShizukuManager.isAvailable()) com.javapro.utils.ShizukuManager.ensureBound()
        delay(1500)
        while (true) {
            withContext(Dispatchers.IO) {
                val clusters = readCpuClustersSuspend()
                if (clusters.isNotEmpty()) {
                    CpuClusterCache.clusters = clusters
                    CpuClusterCache.lastUpdated = System.currentTimeMillis()
                }
                cpuClusters = CpuClusterCache.clusters
                cpuFreqMhz  = clusters.maxOfOrNull { it.currentFreqMhz } ?: 0
                cpuGovernor = try {
                    File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim()
                        .takeIf { it.isNotEmpty() }
                        ?: TweakExecutor.executeWithOutputSync("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").trim()
                } catch (_: Exception) { "" }
            }
            delay(2000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val (curMhz, maxMhz) = resolveGpuFreqAndMax()
                val load             = resolveGpuLoad()
                gpuFreqMhz = curMhz
                gpuMaxMhz  = maxMhz
                gpuLoad    = load
                gpuName    = GpuBackendCache.detectedName
            }
            if (gpuLoad >= 0f) gpuHistory = (gpuHistory + gpuLoad).takeLast(60)
            delay(1500)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                ramInfo     = readRamInfo(context)
                battTempC   = readBatteryTempCelsius(context)
                storageInfo = readStorageInfo()
            }
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("System Monitor", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccessLevelBar(isRooted = isRooted, isShizukuActive = isShizukuActive)

            MonitorSectionLabel("CPU MONITOR")
            CpuMonitorCard(
                usage     = cpuUsage,
                history   = cpuHistory,
                clusters  = cpuClusters,
                cores     = coreUsages,
                freqMhz   = cpuFreqMhz,
                governor  = cpuGovernor,
                tempC     = cpuTempC,
                accentColor = MaterialTheme.colorScheme.primary
            )

            MonitorSectionLabel("GPU MONITOR")
            GpuMonitorCard(
                load      = gpuLoad,
                history   = gpuHistory,
                freqMhz   = gpuFreqMhz,
                maxMhz    = gpuMaxMhz,
                gpuName   = gpuName,
                accessLevel = when {
                    isRooted        -> "Root"
                    isShizukuActive -> "Shizuku"
                    else            -> "Limited"
                },
                accentColor = MaterialTheme.colorScheme.tertiary
            )

            MonitorSectionLabel("RAM MONITOR")
            RamMonitorCard(
                info        = ramInfo,
                battTempC   = battTempC,
                accentColor = MaterialTheme.colorScheme.secondary
            )

            MonitorSectionLabel("STORAGE")
            StorageMonitorCard(
                info        = storageInfo,
                accentColor = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MonitorSectionLabel(text: String) {
    Text(
        text          = text,
        fontSize      = 10.sp,
        fontWeight    = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp,
        color         = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier      = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

@Composable
private fun AccessLevelBar(isRooted: Boolean, isShizukuActive: Boolean) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AccessChip("Root",    isRooted,        Modifier.weight(1f))
        AccessChip("Shizuku", isShizukuActive, Modifier.weight(1f))
        AccessChip("Shell",   true,            Modifier.weight(1f))
    }
}

@Composable
private fun AccessChip(label: String, isActive: Boolean, modifier: Modifier = Modifier) {
    val color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(50),
        color    = color.copy(alpha = 0.1f),
        border   = BorderStroke(0.8.dp, color.copy(0.3f))
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun MonitorCardContainer(
    accentColor : Color,
    modifier    : Modifier = Modifier,
    content     : @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(0.8.dp, accentColor.copy(0.22f)), RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), content = content)
    }
}

@Composable
private fun AreaChart(
    data        : List<Float>,
    color       : Color,
    modifier    : Modifier = Modifier,
    maxOverride : Float    = 0f
) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val maxVal = maxOf(data.max(), maxOverride).coerceAtLeast(1f)
        val step   = size.width / (data.size - 1).coerceAtLeast(1)

        val points = data.mapIndexed { i, v ->
            Offset(
                x = i * step,
                y = size.height - (v / maxVal * size.height * 0.90f + size.height * 0.05f)
            )
        }

        val linePath = Path()
        val fillPath = Path()

        linePath.moveTo(points[0].x, points[0].y)
        fillPath.moveTo(points[0].x, size.height)
        fillPath.lineTo(points[0].x, points[0].y)

        for (i in 1 until points.size) {
            val p = points[i - 1]
            val c = points[i]
            val cx = (p.x + c.x) / 2f
            linePath.cubicTo(cx, p.y, cx, c.y, c.x, c.y)
            fillPath.cubicTo(cx, p.y, cx, c.y, c.x, c.y)
        }

        fillPath.lineTo(points.last().x, size.height)
        fillPath.close()

        drawPath(
            path  = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.01f)),
                startY = 0f,
                endY   = size.height
            )
        )
        drawPath(
            path  = linePath,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (points.isNotEmpty()) {
            val last = points.last()
            drawCircle(color = color.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = last)
            drawCircle(color = color,                     radius = 3.dp.toPx(), center = last)
        }
    }
}

@Composable
private fun UsageProgressBar(progress: Float, color: Color, height: Int = 4) {
    val anim by animateFloatAsState(progress.coerceIn(0f, 1f), tween(500), label = "bar")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(0.12f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(anim)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(color.copy(0.55f), color)))
        )
    }
}

@Composable
private fun MonitorChipRow(chips: List<Pair<String, String>>, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (label, value) ->
            Surface(
                shape  = RoundedCornerShape(10.dp),
                color  = color.copy(0.10f),
                border = BorderStroke(0.7.dp, color.copy(0.25f))
            ) {
                Column(
                    modifier            = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(value, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = color)
                    Text(label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
                }
            }
        }
    }
}

@Composable
private fun StatRow(items: List<Pair<String, String>>, color: Color) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { (label, value) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
private fun CpuMonitorCard(
    usage       : Float,
    history     : List<Float>,
    clusters    : List<CpuClusterInfo>,
    cores       : List<Float>,
    freqMhz     : Int,
    governor    : String,
    tempC       : Float,
    accentColor : Color
) {
    val animUsage by animateFloatAsState(usage, tween(500), label = "cpuUsage")
    val clusterColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.inversePrimary
    )

    MonitorCardContainer(accentColor = accentColor) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("CPU", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
                val sub = buildString {
                    if (freqMhz > 0) append("${freqMhz} MHz")
                    if (governor.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(governor) }
                }
                if (sub.isNotEmpty()) {
                    Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tempC > 0f) {
                    Spacer(Modifier.height(4.dp))
                    Surface(shape = RoundedCornerShape(50), color = accentColor.copy(0.13f)) {
                        Text(
                            "%.1f°C".format(tempC),
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = accentColor,
                            modifier   = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                "${animUsage.toInt()}%",
                fontSize   = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = accentColor,
                lineHeight = 46.sp
            )
        }

        Spacer(Modifier.height(6.dp))
        UsageProgressBar(animUsage / 100f, accentColor)
        Spacer(Modifier.height(8.dp))
        AreaChart(history, accentColor, Modifier.fillMaxWidth().height(80.dp), maxOverride = 100f)

        if (cores.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.35f))
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                cores.forEachIndexed { i, coreUsage ->
                    val animCore by animateFloatAsState(coreUsage / 100f, tween(350), label = "c$i")
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "core$i",
                            fontSize = 9.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(accentColor.copy(0.11f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animCore)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.horizontalGradient(listOf(accentColor.copy(0.5f), accentColor)))
                            )
                        }
                        Text(
                            "${coreUsage.toInt()}%",
                            fontSize  = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color     = accentColor,
                            textAlign = TextAlign.End,
                            modifier  = Modifier.width(28.dp)
                        )
                    }
                }
            }
        }

        if (clusters.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.35f))
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                clusters.forEachIndexed { i, cluster ->
                    val c       = clusterColors[i % clusterColors.size]
                    val prog    = if (cluster.maxFreqMhz > 0) (cluster.currentFreqMhz.toFloat() / cluster.maxFreqMhz).coerceIn(0f, 1f) else 0f
                    val animProg by animateFloatAsState(prog, tween(600), label = "cl$i")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text("${cluster.name} · Core ${cluster.cores.first()}–${cluster.cores.last()}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = c)
                            Text("${cluster.currentFreqMhz} / ${cluster.maxFreqMhz} MHz", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(c.copy(0.12f))
                        ) {
                            Box(Modifier.fillMaxWidth(animProg).fillMaxHeight().clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(c.copy(0.5f), c))))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GpuMonitorCard(
    load        : Float,
    history     : List<Float>,
    freqMhz     : Int,
    maxMhz      : Int,
    gpuName     : String,
    accessLevel : String,
    accentColor : Color
) {
    val noSignal    = load < 0f
    val displayLoad = if (noSignal) 0f else load
    val animLoad    by animateFloatAsState(displayLoad, tween(500), label = "gpuLoad")

    MonitorCardContainer(accentColor = accentColor) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GPU", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
                Text(
                    if (noSignal) "No signal · $gpuName" else gpuName,
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (noSignal) "—" else "${animLoad.toInt()}%",
                fontSize   = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = if (noSignal) MaterialTheme.colorScheme.onSurfaceVariant else accentColor,
                lineHeight = 46.sp
            )
        }

        Spacer(Modifier.height(6.dp))
        UsageProgressBar(animLoad / 100f, accentColor)
        Spacer(Modifier.height(8.dp))

        if (history.size >= 2) {
            AreaChart(history, accentColor, Modifier.fillMaxWidth().height(72.dp), maxOverride = 100f)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Collecting data…",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.35f))
        Spacer(Modifier.height(10.dp))

        MonitorChipRow(
            chips = buildList {
                add("FREQUENCY" to if (freqMhz > 0) "${freqMhz} MHz" else "—")
                add("BACKEND"   to gpuName.take(8).ifEmpty { "—" })
                add("ACCESS"    to accessLevel)
            },
            color = accentColor
        )

        if (maxMhz > 0 && freqMhz > 0) {
            Spacer(Modifier.height(10.dp))
            val maxProg by animateFloatAsState((freqMhz.toFloat() / maxMhz).coerceIn(0f, 1f), tween(600), label = "gpuMax")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text("FREQ RANGE", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
                    Text("$freqMhz / $maxMhz MHz", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = accentColor)
                }
                UsageProgressBar(maxProg, accentColor, height = 4)
            }
        }
    }
}

@Composable
private fun RamMonitorCard(
    info        : RamInfo,
    battTempC   : Float,
    accentColor : Color
) {
    val usedPct  = if (info.totalMb > 0) info.usedMb.toFloat() / info.totalMb * 100f else 0f
    val animPct  by animateFloatAsState(usedPct, tween(500), label = "ramPct")
    val toGbStr  = { mb: Long -> if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB" }

    MonitorCardContainer(accentColor = accentColor) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("RAM", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
                Text(
                    "${toGbStr(info.usedMb)} used of ${toGbStr(info.totalMb)}",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (battTempC > 0f) {
                    Spacer(Modifier.height(4.dp))
                    Surface(shape = RoundedCornerShape(50), color = accentColor.copy(0.13f)) {
                        Text(
                            "Batt %.1f°C".format(battTempC),
                            fontSize   = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = accentColor,
                            modifier   = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                "${animPct.toInt()}%",
                fontSize   = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = accentColor,
                lineHeight = 46.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        UsageProgressBar(animPct / 100f, accentColor, height = 6)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.35f))
        Spacer(Modifier.height(10.dp))

        StatRow(
            items = listOf(
                "USED"      to toGbStr(info.usedMb),
                "AVAILABLE" to toGbStr(info.availMb),
                "TOTAL"     to toGbStr(info.totalMb),
                "CACHED"    to toGbStr(info.cachedMb),
            ),
            color = accentColor
        )
    }
}

@Composable
private fun StorageMonitorCard(
    info        : StorageInfo,
    accentColor : Color
) {
    val usedPct = if (info.totalGb > 0) info.usedGb / info.totalGb * 100f else 0f
    val animPct by animateFloatAsState(usedPct, tween(500), label = "stPct")
    val toStr   = { gb: Float -> "%.1f GB".format(gb) }

    MonitorCardContainer(accentColor = accentColor) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Storage", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
                Text(
                    "${toStr(info.usedGb)} used of ${toStr(info.totalGb)}",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(50), color = accentColor.copy(0.13f)) {
                    Text(
                        info.type,
                        fontSize   = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color      = accentColor,
                        modifier   = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        letterSpacing = 0.5.sp
                    )
                }
            }
            Text(
                "${animPct.toInt()}%",
                fontSize   = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = accentColor,
                lineHeight = 46.sp
            )
        }

        Spacer(Modifier.height(8.dp))
        UsageProgressBar(animPct / 100f, accentColor, height = 6)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.35f))
        Spacer(Modifier.height(10.dp))

        StatRow(
            items = listOf(
                "USED"  to toStr(info.usedGb),
                "FREE"  to toStr(info.freeGb),
                "TOTAL" to toStr(info.totalGb),
                "TYPE"  to info.type,
            ),
            color = accentColor
        )
    }
}
