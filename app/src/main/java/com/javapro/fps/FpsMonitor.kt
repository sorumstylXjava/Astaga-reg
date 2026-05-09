package com.javapro.fps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FpsMonitor(private val executor: ShellExecutor) {

    companion object {
        private const val TAG          = "FpsMonitor"
        private const val HISTORY_SIZE = 120
    }

    private val engine           = FpsEngine(executor)
    private val gpuReader        = GpuReader(executor)

    private val fpsHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val frameTimeHistory = RingBuffer<Float>(HISTORY_SIZE)
    private val cpuHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val gpuHistory       = RingBuffer<Float>(HISTORY_SIZE)
    private val tempHistory      = RingBuffer<Float>(HISTORY_SIZE)

    suspend fun poll(target: ResolvedTarget, refreshHz: Float): FpsUiState =
        withContext(Dispatchers.IO) {
            val pkg = target.pkg

            val engineResult = try {
                engine.poll(target, refreshHz)
            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION_STACKTRACE: engine.poll pkg=$pkg", e)
                EngineResult(emptyList(), FpsStats(), FpsBackend.NONE, "engine: ${e.message}", 0, "")
            }

            fpsHistory.add(engineResult.fps.currentFps)
            frameTimeHistory.add(engineResult.fps.frameTimeMs)

            val sys = try {
                fetchSystemStats()
            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION_STACKTRACE: fetchSystemStats", e)
                SystemStats()
            }

            cpuHistory.add(sys.cpuUsage)
            gpuHistory.add(if (sys.gpuUsage >= 0f) sys.gpuUsage else sys.gpuFreqMhz.toFloat())
            tempHistory.add(sys.batteryTempC)

            FpsUiState(
                fps              = engineResult.fps,
                system           = sys,
                fpsHistory       = fpsHistory.toList(),
                frameTimeHistory = frameTimeHistory.toList(),
                cpuHistory       = cpuHistory.toList(),
                gpuHistory       = gpuHistory.toList(),
                tempHistory      = tempHistory.toList(),
                activeBackend    = engineResult.activeBackend,
                isMonitoring     = true,
                targetPackage    = pkg,
                refreshRateHz    = refreshHz,
                debug = DebugInfo(
                    activeBackend     = engineResult.activeBackend,
                    backendFailReason = engineResult.failReason,
                    targetPackage     = pkg,
                    parsedFrameCount  = engineResult.parsedCount,
                    calculatedFps     = engineResult.fps.currentFps,
                    overlayStatus     = FpsService.overlayStatus,
                    gpuFreqPath       = sys.gpuFreqPath,
                    gpuLoadPath       = sys.gpuLoadPath,
                    gpuFailReason     = sys.gpuFailReason,
                    lastShellOutput   = engineResult.lastShellOutput
                )
            )
        }

    private suspend fun fetchSystemStats(): SystemStats {
        val cpuFreq = executor.run(
            "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"
        )?.trim()?.toLongOrNull()?.div(1000)?.toInt() ?: 0

        val cpuUsage = parseCpuUsage()
        val gpuInfo  = gpuReader.read()
        val tempC    = fetchBatteryTemp()

        Log.d(TAG, "CPU_RESULT: ${cpuUsage}% @${cpuFreq}MHz")
        Log.d(TAG, "GPU_RESULT: ${gpuInfo.freqMhz}MHz load=${gpuInfo.loadPercent}% path=${gpuInfo.freqPath} fail=${gpuInfo.failReason}")

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
        engine.reset()
        gpuReader.reset()
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
