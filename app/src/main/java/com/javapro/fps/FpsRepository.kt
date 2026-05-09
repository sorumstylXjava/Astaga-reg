package com.javapro.fps

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FpsRepository(
    private val monitor: FpsMonitor,
    private val refreshRateHz: Float
) {

    fun observeFps(targetPackage: String, intervalMs: Long = 500L): Flow<FpsUiState> = flow {
        while (true) {
            // Buat ResolvedTarget dari package string — backward compat
            val target = ResolvedTarget(
                pkg      = targetPackage,
                surfaces = listOf(targetPackage, "$targetPackage/", "$targetPackage#0", "")
            )
            val state = monitor.poll(target, refreshRateHz)
            emit(state)
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    fun reset() = monitor.reset()
}
