package com.javapro.fps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FpsMonitor(private val executor: ShellExecutor) {

    companion object {
        private const val TAG = "FpsMonitor"
        private const val HISTORY_SIZE = 120
        private val BACKEND_PRIORITY = listOf(
            FpsBackend.GFXINFO_FRAMESTATS,
            FpsBackend.GFXINFO_TOTALFRAMES,
            FpsBackend.GFXINFO_DRAW_PROCESS,
            FpsBackend.SURFACEFLINGER_LATENCY,
            FpsBackend.SYSFS_MEASURED_FPS,
            FpsBackend.FPSGO
        )
    }

    private var activeBackend     = FpsBackend.NONE
    private var backendFailCount  = 0
    private var failReason        = ""
    private var lastShellOutput   = ""
    private var lastTotalFrames   = -1
    private var lastTotalFramesMs = 0L

    private val frameHistory     = RingBuffer<FrameSample>(512)
    private val fpsHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val frameTimeHistory = RingBuffer<Float>(HISTORY_SIZE)
    private val cpuHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val gpuHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val tempHistory      = RingBuffer<Float>(HISTORY_SIZE)
    private val gpuReader        = GpuReader(executor)

    suspend fun poll(targetPackage: String, refreshRateHz: Float): FpsUiState =
        withContext(Dispatchers.IO) {

            // BUG FIX: pilih backend hanya jika NONE — jangan tiap backendFailCount >= 3
            // rotateFallback() yang handle pergantian saat gagal
            if (activeBackend == FpsBackend.NONE) {
                Log.d(TAG, "FPS_BACKEND_SELECTED: selecting for '$targetPackage'")
                selectBestBackend(targetPackage)
                backendFailCount = 0
            }

            val newFrames = try {
                fetchFrames(targetPackage)
            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION_STACKTRACE: fetchFrames", e)
                emptyList()
            }

            if (newFrames.isEmpty()) {
                backendFailCount++
                failReason = "empty frames: $activeBackend fail=$backendFailCount"
                Log.w(TAG, "FPS_RESULT: $failReason")
                // Rotate setelah 3 gagal berturut-turut, lalu reset counter
                if (backendFailCount >= 3) {
                    rotateFallback()
                    backendFailCount = 0
                }
            } else {
                backendFailCount = 0
                failReason = ""
                newFrames.forEach { frameHistory.add(it) }
                Log.d(TAG, "FPS_RESULT: backend=$activeBackend new=${newFrames.size} total=${frameHistory.size()}")
            }

            val allFrames = frameHistory.toList()
            val stats = FpsCalculator.calculate(allFrames, refreshRateHz)

            fpsHistory.add(stats.currentFps)
            frameTimeHistory.add(stats.frameTimeMs)

            val sysStats = try {
                fetchSystemStats()
            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION_STACKTRACE: fetchSystemStats", e)
                SystemStats()
            }

            cpuHistory.add(sysStats.cpuUsage)
            gpuHistory.add(if (sysStats.gpuUsage >= 0f) sysStats.gpuUsage else sysStats.gpuFreqMhz.toFloat())
            tempHistory.add(sysStats.batteryTempC)

            Log.d(TAG, "CPU_RESULT: ${sysStats.cpuUsage}% @${sysStats.cpuFreqMhz}MHz")
            Log.d(TAG, "GPU_RESULT: ${sysStats.gpuFreqMhz}MHz load=${sysStats.gpuUsage}% fail=${sysStats.gpuFailReason}")

            FpsUiState(
                fps              = stats,
                system           = sysStats,
                fpsHistory       = fpsHistory.toList(),
                frameTimeHistory = frameTimeHistory.toList(),
                cpuHistory       = cpuHistory.toList(),
                gpuHistory       = gpuHistory.toList(),
                tempHistory      = tempHistory.toList(),
                activeBackend    = activeBackend,
                isMonitoring     = true,
                targetPackage    = targetPackage,
                refreshRateHz    = refreshRateHz,
                debug = DebugInfo(
                    activeBackend     = activeBackend,
                    backendFailReason = failReason,
                    targetPackage     = targetPackage,
                    parsedFrameCount  = newFrames.size,
                    calculatedFps     = stats.currentFps,
                    overlayStatus     = FpsService.overlayStatus,
                    gpuFreqPath       = sysStats.gpuFreqPath,
                    gpuLoadPath       = sysStats.gpuLoadPath,
                    gpuFailReason     = sysStats.gpuFailReason,
                    lastShellOutput   = lastShellOutput.take(300)
                )
            )
        }

    // ─── Backend selection ────────────────────────────────────────
    private suspend fun selectBestBackend(pkg: String) {
        for (backend in BACKEND_PRIORITY) {
            Log.d(TAG, "FPS_BACKEND_SELECTED: trying $backend")
            val ok = try {
                tryBackend(backend, pkg)
            } catch (e: Exception) {
                Log.w(TAG, "EXCEPTION_STACKTRACE: tryBackend $backend", e)
                false
            }
            if (ok) {
                activeBackend = backend
                Log.d(TAG, "FPS_BACKEND_SELECTED: ✓ selected=$backend")
                return
            }
        }
        // Jangan set NONE — paksa GFXINFO_TOTALFRAMES sebagai last resort
        // karena hampir semua device support dumpsys gfxinfo
        activeBackend = FpsBackend.GFXINFO_TOTALFRAMES
        failReason    = "no ideal backend, using GFXINFO_TOTALFRAMES as last resort"
        Log.w(TAG, "FPS_BACKEND_SELECTED: last resort → $activeBackend")
    }

    private suspend fun tryBackend(backend: FpsBackend, pkg: String): Boolean {
        return when (backend) {
            FpsBackend.GFXINFO_FRAMESTATS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return false
                lastShellOutput = raw.take(300)
                FramestatsParser.isValid(raw)
                    .also { Log.d(TAG, "tryBackend FRAMESTATS valid=$it len=${raw.length}") }
            }
            FpsBackend.GFXINFO_TOTALFRAMES -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                GfxinfoTotalFramesParser.isValid(raw)
                    .also { Log.d(TAG, "tryBackend TOTALFRAMES valid=$it len=${raw.length}") }
            }
            FpsBackend.GFXINFO_DRAW_PROCESS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                DrawProcessParser.isValid(raw)
                    .also { Log.d(TAG, "tryBackend DRAW_PROCESS valid=$it") }
            }
            FpsBackend.SURFACEFLINGER_LATENCY -> {
                listOf(pkg, "$pkg/", "$pkg#0", "").any { window ->
                    val cmd = if (window.isEmpty()) "dumpsys SurfaceFlinger --latency"
                              else "dumpsys SurfaceFlinger --latency \"$window\""
                    val raw = executor.run(cmd) ?: return@any false
                    lastShellOutput = raw.take(300)
                    SurfaceFlingerParser.isValid(raw)
                        .also { if (it) Log.d(TAG, "tryBackend SF_LATENCY valid=true window='$window'") }
                }.also { if (!it) Log.d(TAG, "tryBackend SF_LATENCY valid=false") }
            }
            FpsBackend.SYSFS_MEASURED_FPS -> {
                listOf(
                    "/sys/class/drm/sde-crtc-0/measured_fps",
                    "/sys/class/drm/card0-DSI-1/measured_fps",
                    "/sys/kernel/gpu/gpu_fps"
                ).any { path ->
                    (executor.run("cat $path")?.trim()?.toFloatOrNull() ?: 0f) > 0f
                }.also { Log.d(TAG, "tryBackend SYSFS_FPS valid=$it") }
            }
            FpsBackend.FPSGO -> {
                val raw = executor.run("cat /proc/fpsgo/fstb/fpsgo_status") ?: return false
                raw.contains("fps", ignoreCase = true)
                    .also { Log.d(TAG, "tryBackend FPSGO valid=$it") }
            }
            FpsBackend.NONE -> false
        }
    }

    // ─── Fetch frames ──────────────────────────────────────────────
    private suspend fun fetchFrames(pkg: String): List<FrameSample> {
        return when (activeBackend) {

            FpsBackend.GFXINFO_FRAMESTATS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return emptyList()
                lastShellOutput = raw.take(300)
                FramestatsParser.parse(raw)
            }

            FpsBackend.GFXINFO_TOTALFRAMES -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
                lastShellOutput = raw.take(300)
                val total = GfxinfoTotalFramesParser.parseTotalFrames(raw) ?: return emptyList()
                val nowMs = System.currentTimeMillis()
                if (lastTotalFrames < 0) {
                    // Pertama kali — simpan baseline
                    lastTotalFrames   = total
                    lastTotalFramesMs = nowMs
                    return emptyList()
                }
                val deltaFrames = total - lastTotalFrames
                val deltaMs     = nowMs - lastTotalFramesMs
                lastTotalFrames   = total
                lastTotalFramesMs = nowMs
                if (deltaFrames <= 0 || deltaMs <= 0) return emptyList()
                val frameTimeMs = deltaMs.toFloat() / deltaFrames
                var ts = System.nanoTime() - deltaFrames * (frameTimeMs * 1_000_000).toLong()
                List(deltaFrames.coerceAtMost(128)) {
                    FrameSample(ts, frameTimeMs).also { ts += (frameTimeMs * 1_000_000).toLong() }
                }
            }

            FpsBackend.GFXINFO_DRAW_PROCESS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
                lastShellOutput = raw.take(300)
                DrawProcessParser.parse(raw)
            }

            FpsBackend.SURFACEFLINGER_LATENCY -> {
                for (window in listOf(pkg, "$pkg/", "$pkg#0")) {
                    val raw = executor.run("dumpsys SurfaceFlinger --latency \"$window\"") ?: continue
                    lastShellOutput = raw.take(300)
                    if (SurfaceFlingerParser.isValid(raw)) return SurfaceFlingerParser.parse(raw)
                }
                emptyList()
            }

            FpsBackend.SYSFS_MEASURED_FPS -> {
                for (path in listOf(
                    "/sys/class/drm/sde-crtc-0/measured_fps",
                    "/sys/class/drm/card0-DSI-1/measured_fps",
                    "/sys/kernel/gpu/gpu_fps"
                )) {
                    val fps = executor.run("cat $path")?.trim()?.toFloatOrNull() ?: continue
                    if (fps <= 0f) continue
                    val ft  = 1000f / fps
                    val now = System.nanoTime()
                    lastShellOutput = "$fps"
                    return listOf(FrameSample(now - (ft * 1_000_000).toLong(), ft), FrameSample(now, ft))
                }
                emptyList()
            }

            FpsBackend.FPSGO -> {
                val raw = executor.run("cat /proc/fpsgo/fstb/fpsgo_status") ?: return emptyList()
                lastShellOutput = raw.take(300)
                val fps = Regex("""fps[=:]\s*(\d+\.?\d*)""").find(raw)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: return emptyList()
                if (fps <= 0f) return emptyList()
                val ft  = 1000f / fps
                val now = System.nanoTime()
                listOf(FrameSample(now - (ft * 1_000_000).toLong(), ft), FrameSample(now, ft))
            }

            FpsBackend.NONE -> emptyList()
        }
    }

    // ─── Fallback rotation ──────────────────────────────────────────
    private fun rotateFallback() {
        val idx  = BACKEND_PRIORITY.indexOf(activeBackend).takeIf { it >= 0 } ?: 0
        val next = BACKEND_PRIORITY[(idx + 1) % BACKEND_PRIORITY.size]
        Log.w(TAG, "FPS_BACKEND_SELECTED: rotate $activeBackend → $next")
        failReason    = "rotated: $activeBackend → $next"
        activeBackend = next
        frameHistory.clear()
        lastTotalFrames   = -1
        lastTotalFramesMs = 0L
    }

    // ─── System stats ───────────────────────────────────────────────
    private suspend fun fetchSystemStats(): SystemStats {
        val cpuFreq = executor.run(
            "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"
        )?.trim()?.toLongOrNull()?.div(1000)?.toInt() ?: 0

        val cpuUsage = parseCpuUsage()
        val gpuInfo  = gpuReader.read()
        val tempC    = fetchBatteryTemp()

        return SystemStats(
            cpuUsage      = cpuUsage,
            cpuFreqMhz    = cpuFreq,
            gpuUsage      = gpuInfo.loadPercent,
            gpuFreqMhz    = gpuInfo.freqMhz,
            gpuFreqPath   = gpuInfo.freqPath,
            gpuLoadPath   = gpuInfo.loadPath,
            gpuFailReason = gpuInfo.failReason,
            batteryTempC  = tempC
        )
    }

    private suspend fun fetchBatteryTemp(): Float {
        for (zone in 0..5) {
            val v = executor.run("cat /sys/class/thermal/thermal_zone$zone/temp")
                ?.trim()?.toLongOrNull() ?: continue
            if (v <= 0L) continue
            return if (v > 1000) v / 1000f else v.toFloat()
        }
        return 0f
    }

    private suspend fun parseCpuUsage(): Float {
        val raw   = executor.run("cat /proc/stat") ?: return 0f
        val line  = raw.lines().firstOrNull { it.startsWith("cpu ") } ?: return 0f
        val parts = line.trim().split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return 0f
        val total = parts.sum()
        val idle  = parts[3]
        return if (total > 0) (1f - idle.toFloat() / total) * 100f else 0f
    }

    fun reset() {
        Log.d(TAG, "FpsMonitor.reset()")
        activeBackend     = FpsBackend.NONE
        backendFailCount  = 0
        failReason        = ""
        lastShellOutput   = ""
        lastTotalFrames   = -1
        lastTotalFramesMs = 0L
        gpuReader.reset()
        frameHistory.clear()
        fpsHistory.clear()
        frameTimeHistory.clear()
        cpuHistory.clear()
        gpuHistory.clear()
        tempHistory.clear()
    }
}

interface ShellExecutor {
    suspend fun run(cmd: String): String?
}
