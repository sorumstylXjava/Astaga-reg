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
        Log.d(TAG, "startMonitoring: called pkg='$targetPackage' isRunning=${pollJob?.isActive}")

        // Duplicate start guard
        if (pollJob?.isActive == true) {
            if (_uiState.value.targetPackage == targetPackage) {
                Log.d(TAG, "startMonitoring: already running same pkg, skip")
                return
            }
            // Package berbeda — stop dulu
            Log.d(TAG, "startMonitoring: different pkg, restarting")
            pollJob?.cancel()
            monitor.reset()
        }

        Log.d(TAG, "startMonitoring: starting pkg='$targetPackage' hz=$refreshRateHz")
        monitor.reset()

        _uiState.value = FpsUiState(
            isMonitoring  = true,
            targetPackage = targetPackage,
            refreshRateHz = refreshRateHz,
            debug         = DebugInfo(
                targetPackage = targetPackage,
                activeBackend = FpsBackend.NONE,
                overlayStatus = FpsService.overlayStatus
            )
        )
        Log.d(TAG, "startMonitoring: state set isMonitoring=true")

        pollJob = viewModelScope.launch {
            Log.d(TAG, "MONITOR_TICK: coroutine started")
            var tick = 0
            while (isActive) {
                try {
                    val state = monitor.poll(targetPackage, refreshRateHz)
                    _uiState.value = state
                    tick++
                    // Log tiap tick pertama, lalu tiap 10
                    if (tick == 1 || tick % 10 == 0) {
                        Log.d(TAG, "MONITOR_TICK: #$tick fps=${state.fps.currentFps} " +
                            "backend=${state.activeBackend} frames=${state.debug.parsedFrameCount} " +
                            "fail='${state.debug.backendFailReason}'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: poll failed tick=$tick", e)
                    _uiState.value = _uiState.value.copy(
                        debug = _uiState.value.debug.copy(
                            backendFailReason = "vm_poll_ex: ${e.message?.take(80)}"
                        )
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
            Log.w(TAG, "UPDATE_LOOP_STOPPED: loop exited tick=$tick isActive=$isActive")
        }

        Log.d(TAG, "startMonitoring: pollJob launched active=${pollJob?.isActive}")
    }

    fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring: called isRunning=${pollJob?.isActive}")
        pollJob?.cancel()
        pollJob = null
        monitor.reset()
        _uiState.value = FpsUiState(isMonitoring = false)
        Log.d(TAG, "stopMonitoring: done")
    }

    fun isRunning(): Boolean = pollJob?.isActive == true

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared: viewmodel cleared")
        stopMonitoring()
    }

    private fun detectRefreshRate(context: Context): Float {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val rate = wm.defaultDisplay.refreshRate
            Log.d(TAG, "detectRefreshRate: ${rate}Hz")
            if (rate > 0f) rate else 60f
        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION_STACKTRACE: detectRefreshRate", e)
            60f
        }
    }
}
