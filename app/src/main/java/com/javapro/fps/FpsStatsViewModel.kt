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
 * ViewModel ini TIDAK memiliki monitoring lifecycle.
 * Ia hanya merelay StateFlow dari FpsMonitorManager (singleton).
 *
 * Sehingga:
 * - navigate away  → monitoring tetap jalan di Manager
 * - navigate back  → ViewModel baru, langsung collect state Manager yang masih hidup
 * - screen dispose → onCleared() dipanggil tapi Manager tidak disentuh
 */
class FpsStatsViewModel(context: Context) : ViewModel() {

    companion object {
        private const val TAG = "FpsStatsVM"
    }

    private val manager = FpsMonitorManager.get(context)

    // Relay langsung dari singleton — tidak ada state sendiri
    val uiState: StateFlow<FpsUiState> = manager.uiState.stateIn(
        scope         = viewModelScope,
        started       = SharingStarted.WhileSubscribed(5_000L),
        initialValue  = manager.uiState.value
    )

    fun startMonitoring(context: Context, targetPackage: String) {
        Log.d(TAG, "startMonitoring: delegating to Manager pkg='$targetPackage'")
        manager.startMonitoring(context, targetPackage)
    }

    fun showOverlay(context: Context) {
        Log.d(TAG, "showOverlay: delegating to Manager")
        manager.showOverlay(context)
    }

    fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring: delegating to Manager")
        manager.stopMonitoring()
    }

    fun hideOverlay() {
        Log.d(TAG, "hideOverlay: delegating to Manager")
        manager.hideOverlay()
    }

    val isMonitoring: Boolean get() = manager.isMonitoring
    val isOverlayVisible: Boolean get() = manager.isOverlayVisible

    override fun onCleared() {
        super.onCleared()
        // JANGAN stop monitoring di sini — Manager tetap hidup
        Log.d(TAG, "onCleared: ViewModel cleared, Manager still alive isMonitoring=${manager.isMonitoring}")
    }
}
