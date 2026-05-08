package com.javapro.fps

/**
 * FpsSessionCache — in-memory global cache.
 * Survive screen navigation dan ViewModel recreation.
 * Tidak survive process kill (by design — tidak perlu persist ke disk).
 */
object FpsSessionCache {
    var latestStats: FpsStats?       = null
    var currentSurface: String?      = null   // resolved surface name dari SurfaceFlinger
    var currentPackage: String       = ""
    var monitorRunning: Boolean      = false
    var overlayVisible: Boolean      = false
    var lastBackend: FpsBackend      = FpsBackend.NONE
    var lastGoodFps: Float           = 0f
    var surfaceCache: MutableMap<String, String> = mutableMapOf()  // pkg → surface

    fun updateFromState(state: FpsUiState) {
        latestStats    = state.fps
        currentPackage = state.targetPackage
        monitorRunning = state.isMonitoring
        lastBackend    = state.activeBackend
        if (state.fps.currentFps > 0f) lastGoodFps = state.fps.currentFps
    }

    fun cacheState(): FpsUiState? {
        val stats = latestStats ?: return null
        return FpsUiState(
            fps           = stats,
            isMonitoring  = monitorRunning,
            targetPackage = currentPackage,
            activeBackend = lastBackend
        )
    }
}
