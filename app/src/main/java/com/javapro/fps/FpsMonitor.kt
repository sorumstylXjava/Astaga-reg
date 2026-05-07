package com.javapro.fps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FpsMonitor(
    private val executor: ShellExecutor
) {
    companion object {
        private const val TAG = "FpsMonitor"
        private const val HISTORY_SIZE = 120
        // Urutan prioritas backend — gfxinfo framestats selalu dicoba duluan
        private val BACKEND_PRIORITY = listOf(
            FpsBackend.GFXINFO_FRAMESTATS,
            FpsBackend.GFXINFO_TOTALFRAMES,
            FpsBackend.GFXINFO_DRAW_PROCESS,
            FpsBackend.SURFACEFLINGER_LATENCY,
            FpsBackend.SYSFS_MEASURED_FPS,
            FpsBackend.FPSGO
        )
    }

    private var activeBackend = FpsBackend.NONE
    private var backendFailCount = 0
    private var failReason = ""
    private var lastShellOutput = ""

    // Untuk GFXINFO_TOTALFRAMES — hitung delta
    private var lastTotalFrames = -1
    private var lastTotalFramesMs = 0L

    // Frame history untuk kalkulasi FPS
    private val frameHistory     = RingBuffer<FrameSample>(512)
    private val fpsHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val frameTimeHistory = RingBuffer<Float>(HISTORY_SIZE)
    private val cpuHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val gpuHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val tempHistory      = RingBuffer<Float>(HISTORY_SIZE)

    // GPU reader — multi-path detection dengan cache
    private val gpuReader = GpuReader(executor)

    suspend fun poll(targetPackage: String, refreshRateHz: Float): FpsUiState =
        withContext(Dispatchers.IO) {

            // Pilih backend jika belum ada atau sering gagal
            if (activeBackend == FpsBackend.NONE || backendFailCount >= 3) {
                selectBestBackend(targetPackage)
                backendFailCount = 0
            }

            val newFrames = fetchFrames(targetPackage)

            if (newFrames.isEmpty()) {
                backendFailCount++
                failReason = "No frames from $activeBackend (fail #$backendFailCount)"
                Log.w(TAG, failReason)
                if (backendFailCount >= 3) {
                    rotateFallback()
                }
            } else {
                backendFailCount = 0
                failReason = ""
                newFrames.forEach { frameHistory.add(it) }
            }

            val allFrames = frameHistory.toList()
            val stats = FpsCalculator.calculate(allFrames, refreshRateHz)

            fpsHistory.add(stats.currentFps)
            frameTimeHistory.add(stats.frameTimeMs)

            val sysStats = fetchSystemStats()
            cpuHistory.add(sysStats.cpuUsage)
            // Pakai GPU load jika tersedia, fallback ke freq untuk history chart
            gpuHistory.add(
                if (sysStats.gpuUsage >= 0f) sysStats.gpuUsage
                else sysStats.gpuFreqMhz.toFloat()
            )
            tempHistory.add(sysStats.batteryTempC)

            val debug = DebugInfo(
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
                debug            = debug
            )
        }

    // ─── Backend selection ──────────────────────────────────────
    private suspend fun selectBestBackend(pkg: String) {
        Log.d(TAG, "Selecting backend for '$pkg'")
        for (backend in BACKEND_PRIORITY) {
            if (tryBackend(backend, pkg)) {
                activeBackend = backend
                Log.d(TAG, "Selected backend: $backend")
                return
            }
        }
        // Tidak ada backend valid — tetap di NONE
        activeBackend = FpsBackend.NONE
        failReason = "No valid backend found for $pkg"
        Log.w(TAG, failReason)
    }

    private suspend fun tryBackend(backend: FpsBackend, pkg: String): Boolean {
        return when (backend) {
            FpsBackend.GFXINFO_FRAMESTATS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return false
                lastShellOutput = raw.take(300)
                FramestatsParser.isValid(raw)
            }
            FpsBackend.GFXINFO_TOTALFRAMES -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                GfxinfoTotalFramesParser.isValid(raw)
            }
            FpsBackend.GFXINFO_DRAW_PROCESS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                DrawProcessParser.isValid(raw)
            }
            FpsBackend.SURFACEFLINGER_LATENCY -> {
                // Coba beberapa format window name
                val candidates = listOf(
                    pkg,
                    "$pkg/",
                    "$pkg#0",
                    // Coba tanpa package — ambil window aktif
                    ""
                )
                for (window in candidates) {
                    val cmd = if (window.isEmpty())
                        "dumpsys SurfaceFlinger --latency"
                    else
                        "dumpsys SurfaceFlinger --latency \"$window\""
                    val raw = executor.run(cmd) ?: continue
                    lastShellOutput = raw.take(300)
                    if (SurfaceFlingerParser.isValid(raw)) return true
                }
                false
            }
            FpsBackend.SYSFS_MEASURED_FPS -> {
                val paths = listOf(
                    "/sys/class/drm/sde-crtc-0/measured_fps",
                    "/sys/class/drm/card0-DSI-1/measured_fps",
                    "/sys/kernel/gpu/gpu_fps"
                )
                paths.any { path ->
                    val raw = executor.run("cat $path") ?: return@any false
                    raw.trim().toFloatOrNull()?.let { it > 0f } ?: false
                }
            }
            FpsBackend.FPSGO -> {
                val raw = executor.run("cat /proc/fpsgo/fstb/fpsgo_status") ?: return false
                raw.contains("fps") || raw.contains("FPS")
            }
            FpsBackend.NONE -> false
        }
    }

    // ─── Fetch frames berdasarkan backend aktif ─────────────────
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
                    // Init — simpan baseline, tidak ada frame untuk dihitung
                    lastTotalFrames = total
                    lastTotalFramesMs = nowMs
                    return emptyList()
                }

                val deltaFrames = total - lastTotalFrames
                val deltaMs = nowMs - lastTotalFramesMs

                lastTotalFrames = total
                lastTotalFramesMs = nowMs

                if (deltaFrames <= 0 || deltaMs <= 0) return emptyList()

                // Buat synthetic frame samples dari delta
                val frameTimeMs = deltaMs.toFloat() / deltaFrames
                val samples = mutableListOf<FrameSample>()
                var ts = System.nanoTime() - deltaFrames * (frameTimeMs * 1_000_000).toLong()
                repeat(deltaFrames.coerceAtMost(128)) {
                    samples.add(FrameSample(timestamp = ts, frameTimeMs = frameTimeMs))
                    ts += (frameTimeMs * 1_000_000).toLong()
                }
                samples
            }

            FpsBackend.GFXINFO_DRAW_PROCESS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
                lastShellOutput = raw.take(300)
                DrawProcessParser.parse(raw)
            }

            FpsBackend.SURFACEFLINGER_LATENCY -> {
                val candidates = listOf(pkg, "$pkg/", "$pkg#0")
                for (window in candidates) {
                    val raw = executor.run("dumpsys SurfaceFlinger --latency \"$window\"")
                        ?: continue
                    lastShellOutput = raw.take(300)
                    if (SurfaceFlingerParser.isValid(raw)) {
                        return SurfaceFlingerParser.parse(raw)
                    }
                }
                emptyList()
            }

            FpsBackend.SYSFS_MEASURED_FPS -> {
                val paths = listOf(
                    "/sys/class/drm/sde-crtc-0/measured_fps",
                    "/sys/class/drm/card0-DSI-1/measured_fps",
                    "/sys/kernel/gpu/gpu_fps"
                )
                for (path in paths) {
                    val raw = executor.run("cat $path") ?: continue
                    val fps = raw.trim().toFloatOrNull() ?: continue
                    if (fps <= 0f) continue
                    lastShellOutput = raw
                    // Buat synthetic samples dari fps value
                    val frameTimeMs = 1000f / fps
                    val now = System.nanoTime()
                    return listOf(
                        FrameSample(now - (frameTimeMs * 1_000_000).toLong(), frameTimeMs),
                        FrameSample(now, frameTimeMs)
                    )
                }
                emptyList()
            }

            FpsBackend.FPSGO -> {
                val raw = executor.run("cat /proc/fpsgo/fstb/fpsgo_status") ?: return emptyList()
                lastShellOutput = raw.take(300)
                // Parse baris seperti: "pid=XXX fps=60.0"
                val fpsMatch = Regex("""fps[=:]\s*(\d+\.?\d*)""").find(raw)
                val fps = fpsMatch?.groupValues?.get(1)?.toFloatOrNull() ?: return emptyList()
                if (fps <= 0f) return emptyList()
                val frameTimeMs = 1000f / fps
                val now = System.nanoTime()
                listOf(
                    FrameSample(now - (frameTimeMs * 1_000_000).toLong(), frameTimeMs),
                    FrameSample(now, frameTimeMs)
                )
            }

            FpsBackend.NONE -> emptyList()
        }
    }

    // ─── Fallback rotation ─────────────────────────────────────
    private fun rotateFallback() {
        val currentIdx = BACKEND_PRIORITY.indexOf(activeBackend)
        val nextIdx = (currentIdx + 1) % BACKEND_PRIORITY.size
        val next = BACKEND_PRIORITY[nextIdx]
        Log.d(TAG, "Rotate fallback: $activeBackend → $next")
        failReason = "Rotated from $activeBackend to $next"
        activeBackend = next
        frameHistory.clear()
        // Reset TotalFrames counter saat ganti backend
        lastTotalFrames = -1
        lastTotalFramesMs = 0L
    }

    // ─── System stats — pakai GpuReader untuk GPU ──────────────
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
            gpuUsage      = gpuInfo.loadPercent,      // -1 jika tidak terbaca
            gpuFreqMhz    = gpuInfo.freqMhz,
            gpuFreqPath   = gpuInfo.freqPath,
            gpuLoadPath   = gpuInfo.loadPath,
            gpuFailReason = gpuInfo.failReason,
            batteryTempC  = tempC,
            powerW        = 0f
        )
    }

    private suspend fun fetchBatteryTemp(): Float {
        for (zone in 0..5) {
            val raw = executor.run("cat /sys/class/thermal/thermal_zone$zone/temp")
                ?.trim()?.toLongOrNull() ?: continue
            if (raw <= 0L) continue
            return if (raw > 1000) raw / 1000f else raw.toFloat()
        }
        return 0f
    }

    private suspend fun parseCpuUsage(): Float {
        val raw = executor.run("cat /proc/stat") ?: return 0f
        val line = raw.lines().firstOrNull { it.startsWith("cpu ") } ?: return 0f
        val parts = line.trim().split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return 0f
        val total = parts.sum()
        val idle  = parts[3]
        return if (total > 0) (1f - idle.toFloat() / total) * 100f else 0f
    }

    fun reset() {
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
