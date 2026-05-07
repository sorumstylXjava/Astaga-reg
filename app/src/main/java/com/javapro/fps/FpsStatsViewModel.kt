package com.javapro.fps

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FpsStatsViewModel(
    context: Context,
    private val shellExecutor: ShellExecutor
) : ViewModel() {

    companion object {
        private const val TAG              = "FpsStatsVM"
        private const val POLL_INTERVAL_MS = 500L
    }

    private val _uiState = MutableStateFlow(FpsUiState())
    val uiState: StateFlow<FpsUiState> = _uiState.asStateFlow()

    private val monitor       = FpsMonitor(shellExecutor)
    private var pollJob: Job? = null
    private val refreshRateHz = detectRefreshRate(context)

    fun startMonitoring(targetPackage: String) {
        if (pollJob?.isActive == true) {
            Log.d(TAG, "MONITOR_START: already running for '${_uiState.value.targetPackage}', skip")
            return
        }
        Log.d(TAG, "MONITOR_START: pkg=$targetPackage hz=$refreshRateHz")
        monitor.reset()

        // Set state langsung agar UI update sebelum loop pertama
        _uiState.value = FpsUiState(
            isMonitoring  = true,
            targetPackage = targetPackage,
            refreshRateHz = refreshRateHz,
            debug         = DebugInfo(
                targetPackage = targetPackage,
                overlayStatus = FpsService.overlayStatus
            )
        )

        pollJob = viewModelScope.launch {
            Log.d(TAG, "MONITOR_TICK: loop started")
            var tick = 0
            while (isActive) {
                try {
                    val state = monitor.poll(targetPackage, refreshRateHz)
                    _uiState.value = state
                    tick++
                    if (tick % 10 == 0) {
                        Log.d(TAG, "MONITOR_TICK: #$tick fps=${state.fps.currentFps} " +
                            "backend=${state.activeBackend} frames=${state.debug.parsedFrameCount}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: poll failed", e)
                    // Update debug info dengan error — jangan hentikan loop
                    _uiState.value = _uiState.value.copy(
                        debug = _uiState.value.debug.copy(
                            backendFailReason = "Exception: ${e.message?.take(80)}"
                        )
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
            Log.w(TAG, "UPDATE_LOOP_STOPPED: isActive=$isActive")
        }
    }

    fun stopMonitoring() {
        Log.d(TAG, "MONITOR_STOP")
        pollJob?.cancel()
        pollJob = null
        monitor.reset()
        _uiState.value = FpsUiState(isMonitoring = false)
    }

    fun isRunning(): Boolean = pollJob?.isActive == true

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "MONITOR_CLEARED")
        stopMonitoring()
    }

    private fun detectRefreshRate(context: Context): Float {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val rate = wm.defaultDisplay.refreshRate
            Log.d(TAG, "REFRESH_RATE: ${rate}Hz")
            if (rate > 0f) rate else 60f
        } catch (e: Exception) {
            Log.w(TAG, "REFRESH_RATE: fallback 60Hz — ${e.message}")
            60f
        }
    }
}
