package com.javapro.fps

data class FrameSample(
    val timestamp: Long,
    val frameTimeMs: Float
)

data class FpsStats(
    val currentFps: Float = 0f,
    val avgFps: Float = 0f,
    val minFps: Float = 0f,
    val maxFps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val maxFrameTimeMs: Float = 0f,
    val variance: Float = 0f,
    val smoothness: Float = 100f,
    val fps1Low: Float = 0f,
    val fps5Low: Float = 0f,
    val jankCount: Int = 0,
    val bigJankCount: Int = 0,
    val totalFrames: Int = 0
)

data class SystemStats(
    val cpuUsage: Float = 0f,
    val cpuFreqMhz: Int = 0,
    val gpuUsage: Float = -1f,       // -1 = tidak terbaca
    val gpuFreqMhz: Int = 0,
    val gpuFreqPath: String = "--",
    val gpuLoadPath: String = "--",
    val gpuFailReason: String = "",
    val batteryTempC: Float = 0f,
    val powerW: Float = 0f
)

data class DebugInfo(
    val activeBackend: FpsBackend = FpsBackend.NONE,
    val backendFailReason: String = "",
    val targetPackage: String = "",
    val parsedFrameCount: Int = 0,
    val calculatedFps: Float = 0f,
    val overlayStatus: String = "off",
    val gpuFreqPath: String = "--",
    val gpuLoadPath: String = "--",
    val gpuFailReason: String = "",
    val lastShellOutput: String = ""
)

data class FpsUiState(
    val fps: FpsStats = FpsStats(),
    val system: SystemStats = SystemStats(),
    val fpsHistory: List<Float> = emptyList(),
    val frameTimeHistory: List<Float> = emptyList(),
    val cpuHistory: List<Float> = emptyList(),
    val gpuHistory: List<Float> = emptyList(),
    val tempHistory: List<Float> = emptyList(),
    val activeBackend: FpsBackend = FpsBackend.NONE,
    val isMonitoring: Boolean = false,
    val targetPackage: String = "",
    val refreshRateHz: Float = 60f,
    val debug: DebugInfo = DebugInfo()
)

enum class FpsBackend {
    NONE,
    GFXINFO_FRAMESTATS,    // dumpsys gfxinfo <pkg> framestats   — prioritas utama
    GFXINFO_TOTALFRAMES,   // dumpsys gfxinfo <pkg> — parse "Total frames rendered: X"
    SURFACEFLINGER_LATENCY,// dumpsys SurfaceFlinger --latency    — hanya jika valid banyak frame
    GFXINFO_DRAW_PROCESS,  // dumpsys gfxinfo <pkg> — parse baris Draw/Process/Execute
    SYSFS_MEASURED_FPS,    // /sys/class/drm/.../measured_fps
    FPSGO                  // /proc/fpsgo/...
}
