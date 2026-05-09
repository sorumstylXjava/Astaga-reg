package com.javapro.fps

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * FpsStatsViewModel — THIN OBSERVER ONLY.
 *
 * Tidak memiliki coroutine monitoring sendiri.
 * Semua delegate ke FpsMonitorManager (object singleton).
 *
 * onCleared() TIDAK stop monitoring — Manager tetap hidup.
 * Screen navigate away → ViewModel cleared → Manager tetap poll.
 * Screen navigate back → ViewModel baru → langsung collect existing state.
 */
class FpsStatsViewModel(private val context: Context) : ViewModel() {

    companion object {
        private const val TAG = "FpsStatsVM"
    }

    // Relay dari singleton — SharingStarted.Eagerly agar selalu aktif
    // (bukan WhileSubscribed yang bisa stop jika tidak ada subscriber 5 detik)
    val uiState: StateFlow<FpsUiState> = FpsMonitorManager.uiState.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = FpsMonitorManager.reconnect()
    )

    /**
     * Start auto-target monitoring — tidak butuh package manual.
     * AutoTargetResolver akan detect foreground app setiap polling cycle.
     */
    fun startMonitoring(context: Context) {
        Log.d(TAG, "startMonitoring(auto) → Manager")
        FpsMonitorManager.startMonitoring(context)
    }

    fun startMonitoring(context: Context, pkg: String) {
        Log.d(TAG, "startMonitoring(pkg=$pkg) → Manager (auto-target)")
        FpsMonitorManager.startMonitoring(context)  // pkg ignored — auto detect
    }

    fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring → Manager")
        FpsMonitorManager.stopMonitoring()
    }

    fun showOverlay(context: Context) {
        Log.d(TAG, "showOverlay → Manager")
        FpsMonitorManager.showOverlay(context)
    }

    fun hideOverlay() {
        Log.d(TAG, "hideOverlay → Manager")
        FpsMonitorManager.hideOverlay()
    }

    val isMonitoring: Boolean    get() = FpsMonitorManager.isMonitoring
    val isOverlayVisible: Boolean get() = FpsMonitorManager.isOverlayVisible

    override fun onCleared() {
        super.onCleared()
        // JANGAN stop monitoring — Manager independent dari ViewModel lifecycle
        Log.d(TAG, "onCleared: VM cleared, Manager still alive " +
            "isMonitoring=${FpsMonitorManager.isMonitoring}")
    }
}
