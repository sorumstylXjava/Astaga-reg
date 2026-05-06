package com.javapro.fps

import android.content.Context
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
    shellExecutor: ShellExecutor
) : ViewModel() {

    private val _uiState = MutableStateFlow(FpsUiState())
    val uiState: StateFlow<FpsUiState> = _uiState.asStateFlow()

    private val monitor = FpsMonitor(shellExecutor)
    private var pollJob: Job? = null
    private val refreshRateHz = detectRefreshRate(context)

    fun startMonitoring(targetPackage: String) {
        if (pollJob?.isActive == true) return
        monitor.reset()
        pollJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isMonitoring = true,
                targetPackage = targetPackage,
                refreshRateHz = refreshRateHz
            )
            while (isActive) {
                val state = monitor.poll(targetPackage, refreshRateHz)
                _uiState.value = state
                delay(500L)
            }
        }
    }

    fun stopMonitoring() {
        pollJob?.cancel()
        pollJob = null
        _uiState.value = _uiState.value.copy(isMonitoring = false)
        monitor.reset()
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }

    private fun detectRefreshRate(context: Context): Float {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val rate = wm.defaultDisplay.refreshRate
            if (rate > 0f) rate else 60f
        } catch (e: Exception) {
            60f
        }
    }
}
