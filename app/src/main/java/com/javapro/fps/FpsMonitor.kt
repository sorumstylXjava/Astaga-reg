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
        private const val FROZEN_THRESHOLD_MS = 3000L
    }

    private var activeBackend = FpsBackend.NONE
    private var backendFailCount = 0
    private var lastFrameTimestamp = 0L
    private var lastPollMs = 0L
    private val frameHistory = RingBuffer<FrameSample>(512)

    private val fpsHistory     = RingBuffer<Float>(HISTORY_SIZE)
    private val frameTimeHistory = RingBuffer<Float>(HISTORY_SIZE)
    private val cpuHistory     = RingBuffer<Float>(HISTORY_SIZE)
    private val gpuHistory     = RingBuffer<Float>(HISTORY_SIZE)
    private val tempHistory    = RingBuffer<Float>(HISTORY_SIZE)

    suspend fun poll(
        targetPackage: String,
        refreshRateHz: Float
    ): FpsUiState = withContext(Dispatchers.IO) {
        if (activeBackend == FpsBackend.NONE || backendFailCount >= 3) {
            selectBestBackend(targetPackage)
        }

        val newFrames = fetchFrames(targetPackage)
        val isFrozen = detectFrozen(newFrames)

        if (newFrames.isEmpty() || isFrozen) {
            backendFailCount++
            Log.d(TAG, "Backend $activeBackend failed ($backendFailCount), frozen=$isFrozen")
            if (backendFailCount >= 3) {
                fallbackBackend()
                backendFailCount = 0
            }
        } else {
            backendFailCount = 0
            newFrames.forEach { frameHistory.add(it) }
            if (newFrames.isNotEmpty()) lastFrameTimestamp = newFrames.last().timestamp
        }

        val allFrames = frameHistory.toList()
        val stats = FpsCalculator.calculate(allFrames, refreshRateHz)

        fpsHistory.add(stats.currentFps)
        frameTimeHistory.add(stats.frameTimeMs)

        val sysStats = fetchSystemStats()
        cpuHistory.add(sysStats.cpuUsage)
        gpuHistory.add(sysStats.gpuUsage)
        tempHistory.add(sysStats.batteryTempC)

        FpsUiState(
            fps = stats,
            system = sysStats,
            fpsHistory = fpsHistory.toList(),
            frameTimeHistory = frameTimeHistory.toList(),
            cpuHistory = cpuHistory.toList(),
            gpuHistory = gpuHistory.toList(),
            tempHistory = tempHistory.toList(),
            activeBackend = activeBackend,
            isMonitoring = true,
            targetPackage = targetPackage,
            refreshRateHz = refreshRateHz
        )
    }

    private suspend fun selectBestBackend(pkg: String) {
        Log.d(TAG, "Selecting best backend for $pkg")

        val sfRaw = executor.run("dumpsys SurfaceFlinger --latency \"$pkg\"")
        if (!sfRaw.isNullOrBlank() && SurfaceFlingerParser.isValid(sfRaw)) {
            activeBackend = FpsBackend.SURFACEFLINGER_LATENCY
            Log.d(TAG, "Backend: SURFACEFLINGER_LATENCY")
            return
        }

        val gfxRaw = executor.run("dumpsys gfxinfo $pkg framestats")
        if (!gfxRaw.isNullOrBlank() && FramestatsParser.isValid(gfxRaw)) {
            activeBackend = FpsBackend.GFXINFO_FRAMESTATS
            Log.d(TAG, "Backend: GFXINFO_FRAMESTATS")
            return
        }

        activeBackend = FpsBackend.SURFACEFLINGER_FALLBACK
        Log.d(TAG, "Backend: SURFACEFLINGER_FALLBACK (last resort)")
    }

    private suspend fun fetchFrames(pkg: String): List<FrameSample> {
        return when (activeBackend) {
            FpsBackend.SURFACEFLINGER_LATENCY -> {
                val raw = executor.run("dumpsys SurfaceFlinger --latency \"$pkg\"") ?: return emptyList()
                if (SurfaceFlingerParser.isOnlyRefreshRate(raw)) {
                    Log.d(TAG, "SF latency only returned refresh period, switching")
                    fallbackBackend()
                    return emptyList()
                }
                SurfaceFlingerParser.parse(raw)
            }
            FpsBackend.GFXINFO_FRAMESTATS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return emptyList()
                FramestatsParser.parse(raw)
            }
            FpsBackend.SURFACEFLINGER_FALLBACK -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
                parseFallbackGfxinfo(raw)
            }
            FpsBackend.NONE -> emptyList()
        }
    }

    private fun parseFallbackGfxinfo(raw: String): List<FrameSample> {
        val result = mutableListOf<FrameSample>()
        var tsBase = System.nanoTime()
        for (line in raw.lines()) {
            if (line.contains("Draw") || line.contains("Process") || line.contains("Execute")) continue
            val match = Regex("""(\d+\.?\d*)\s+(\d+\.?\d*)\s+(\d+\.?\d*)""").find(line) ?: continue
            val draw    = match.groupValues[1].toFloatOrNull() ?: continue
            val process = match.groupValues[2].toFloatOrNull() ?: continue
            val execute = match.groupValues[3].toFloatOrNull() ?: continue
            val total = draw + process + execute
            if (total <= 0f || total > 5000f) continue
            result.add(FrameSample(timestamp = tsBase, frameTimeMs = total))
            tsBase += (total * 1_000_000).toLong()
        }
        return result
    }

    private fun detectFrozen(frames: List<FrameSample>): Boolean {
        if (frames.isEmpty()) return true
        val now = System.currentTimeMillis()
        if (lastPollMs > 0 && now - lastPollMs > FROZEN_THRESHOLD_MS) {
            val lastTs = frames.lastOrNull()?.timestamp ?: 0L
            if (lastTs == lastFrameTimestamp && lastFrameTimestamp > 0) return true
        }
        lastPollMs = now
        return false
    }

    private fun fallbackBackend() {
        val next = when (activeBackend) {
            FpsBackend.SURFACEFLINGER_LATENCY -> FpsBackend.GFXINFO_FRAMESTATS
            FpsBackend.GFXINFO_FRAMESTATS     -> FpsBackend.SURFACEFLINGER_FALLBACK
            FpsBackend.SURFACEFLINGER_FALLBACK -> FpsBackend.GFXINFO_FRAMESTATS
            FpsBackend.NONE                   -> FpsBackend.GFXINFO_FRAMESTATS
        }
        Log.d(TAG, "Fallback: $activeBackend -> $next")
        activeBackend = next
        frameHistory.clear()
    }

    private suspend fun fetchSystemStats(): SystemStats {
        val cpuFreq = executor.run(
            "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"
        )?.trim()?.toLongOrNull()?.div(1000)?.toInt() ?: 0

        val cpuUsage = parseCpuUsage()

        val gpuFreq = executor.run(
            "cat /sys/class/kgsl/kgsl-3d0/gpuclk"
        )?.trim()?.toLongOrNull()?.div(1_000_000)?.toInt()
            ?: executor.run("cat /sys/class/devfreq/*/cur_freq")?.trim()
                ?.toLongOrNull()?.div(1_000_000)?.toInt() ?: 0

        val tempRaw = executor.run("cat /sys/class/thermal/thermal_zone0/temp")
            ?.trim()?.toLongOrNull() ?: 0L
        val tempC = if (tempRaw > 1000) tempRaw / 1000f else tempRaw.toFloat()

        return SystemStats(
            cpuUsage = cpuUsage,
            cpuFreqMhz = cpuFreq,
            gpuUsage = 0f,
            gpuFreqMhz = gpuFreq,
            batteryTempC = tempC,
            powerW = 0f
        )
    }

    private suspend fun parseCpuUsage(): Float {
        val raw = executor.run("cat /proc/stat") ?: return 0f
        val line = raw.lines().firstOrNull { it.startsWith("cpu ") } ?: return 0f
        val parts = line.trim().split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return 0f
        val total = parts.sum()
        val idle = parts[3]
        return if (total > 0) (1f - idle.toFloat() / total) * 100f else 0f
    }

    fun reset() {
        activeBackend = FpsBackend.NONE
        backendFailCount = 0
        lastFrameTimestamp = 0L
        lastPollMs = 0L
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
