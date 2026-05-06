package com.javapro.fps

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FpsRepository(
    private val monitor: FpsMonitor,
    private val refreshRateHz: Float
) {

    fun observeFps(targetPackage: String, intervalMs: Long = 500L): Flow<FpsUiState> = flow {
        while (true) {
            val state = monitor.poll(targetPackage, refreshRateHz)
            emit(state)
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    fun reset() = monitor.reset()
}
