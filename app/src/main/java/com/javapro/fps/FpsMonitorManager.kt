package com.javapro.fps

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FpsMonitorManager — SINGLETON.
 *
 * Persistent monitor yang TIDAK terikat lifecycle screen/ViewModel.
 * Screen hanya observe uiState flow.
 * Overlay di-manage di sini — survive navigation, background, game launch.
 *
 * Usage:
 *   FpsMonitorManager.get(context).startMonitoring("com.example.game")
 *   FpsMonitorManager.get(context).showOverlay(context)
 *   val state by FpsMonitorManager.get(context).uiState.collectAsState()
 */
class FpsMonitorManager private constructor(
    private val appContext: Context
) : LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG         = "FpsStatsVM"   // sama dengan ViewModel tag agar filter mudah
        private const val TAG_OVERLAY = "FpsOverlay"
        private const val POLL_MS     = 500L
        private const val HEARTBEAT_MS = 5_000L

        @Volatile
        private var INSTANCE: FpsMonitorManager? = null

        fun get(context: Context): FpsMonitorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FpsMonitorManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    // ── Lifecycle owner untuk ComposeView overlay ────────────────
    private val lifecycleRegistry            = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle        get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Log.d(TAG, "FpsMonitorManager: singleton created")
    }

    // ── State ────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(FpsUiState())
    val uiState: StateFlow<FpsUiState> = _uiState.asStateFlow()

    val isMonitoring: Boolean get() = pollJob?.isActive == true
    val isOverlayVisible: Boolean get() = overlayView != null

    // ── Internal ─────────────────────────────────────────────────
    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job?       = null
    private var heartbeatJob: Job?  = null

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView?     = null
    private var currentPkg  = ""
    private var refreshHz   = 60f

    private val overlayStateFlow = MutableStateFlow(FpsUiState())

    // ── Start monitoring ─────────────────────────────────────────
    fun startMonitoring(context: Context, targetPackage: String) {
        Log.d(TAG, "startMonitoring: pkg='$targetPackage' wasRunning=$isMonitoring")

        if (pollJob?.isActive == true && currentPkg == targetPackage) {
            Log.d(TAG, "startMonitoring: same pkg already running, skip")
            return
        }
        if (pollJob?.isActive == true) {
            Log.d(TAG, "startMonitoring: different pkg, restarting from $currentPkg to $targetPackage")
            pollJob?.cancel()
        }

        currentPkg = targetPackage
        refreshHz  = RefreshRateDetector.detect(context)

        val executor = TweakShellExecutor(context)
        val monitor  = FpsMonitor(executor)
        monitor.reset()

        Log.d(TAG, "startMonitoring: launching coroutine hz=$refreshHz")

        _uiState.value = FpsUiState(
            isMonitoring  = true,
            targetPackage = targetPackage,
            refreshRateHz = refreshHz,
            debug         = DebugInfo(targetPackage = targetPackage, activeBackend = FpsBackend.NONE)
        )

        pollJob = scope.launch {
            Log.d(TAG, "MONITOR_TICK: loop started")
            var tick = 0
            while (isActive) {
                try {
                    val state = monitor.poll(targetPackage, refreshHz)
                    _uiState.value       = state
                    overlayStateFlow.value = state
                    tick++
                    if (tick == 1 || tick % 10 == 0) {
                        Log.d(TAG, "MONITOR_TICK: #$tick fps=${state.fps.currentFps} " +
                            "backend=${state.activeBackend} frames=${state.debug.parsedFrameCount}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: poll tick=$tick", e)
                    _uiState.value = _uiState.value.copy(
                        debug = _uiState.value.debug.copy(
                            backendFailReason = "ex: ${e.message?.take(80)}"
                        )
                    )
                }
                delay(POLL_MS)
            }
            Log.w(TAG, "UPDATE_LOOP_STOPPED: loop exited tick=$tick")
        }

        Log.d(TAG, "startMonitoring: pollJob active=${pollJob?.isActive}")
    }

    fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring: called")
        pollJob?.cancel()
        pollJob = null
        currentPkg = ""
        _uiState.value = FpsUiState(isMonitoring = false)
    }

    // ── Overlay ──────────────────────────────────────────────────
    fun showOverlay(context: Context) {
        Log.d(TAG_OVERLAY, "showOverlay: called isVisible=$isOverlayVisible")

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG_OVERLAY, "OVERLAY_FAILED: permission not granted")
            updateOverlayStatus("no_permission")
            return
        }

        if (overlayView != null) {
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: already visible, skip")
            return
        }

        if (windowManager == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        val wm = windowManager!!

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 80
        }

        Log.d(TAG_OVERLAY, "showOverlay: creating ComposeView type=$type")

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FpsMonitorManager)
            setViewTreeSavedStateRegistryOwner(this@FpsMonitorManager)
            visibility = View.VISIBLE
            setContent {
                val s = overlayStateFlow.collectAsState()
                // DEBUG LAYER — background merah semi transparan agar terlihat saat testing
                Box(
                    modifier = Modifier
                        .background(Color.Red.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fps = s.value.fps.currentFps
                    Text(
                        text       = if (fps > 0f) "%.0f FPS".format(fps) else "OVERLAY ACTIVE",
                        color      = Color.White,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                // ─── Ganti baris di atas dengan FpsBubble setelah overlay confirmed working:
                // com.javapro.fps.ui.FpsBubble(
                //     fps           = s.value.fps.currentFps,
                //     refreshRateHz = s.value.refreshRateHz,
                //     frameTimeMs   = s.value.fps.frameTimeMs
                // )
            }
        }

        try {
            wm.addView(view, params)
            overlayView = view
            updateOverlayStatus("active")
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: addView success type=$type")
            startHeartbeat(context)
        } catch (e: Exception) {
            updateOverlayStatus("addView_error: ${e.message?.take(50)}")
            Log.e(TAG_OVERLAY, "OVERLAY_FAILED: addView exception", e)
        }
    }

    fun hideOverlay() {
        Log.d(TAG_OVERLAY, "hideOverlay: called isVisible=$isOverlayVisible")
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (overlayView == null) return
        try {
            windowManager?.removeViewImmediate(overlayView!!)
            Log.d(TAG_OVERLAY, "hideOverlay: removed ok")
        } catch (e: Exception) {
            Log.e(TAG_OVERLAY, "OVERLAY_FAILED: removeView ${e.message}")
        }
        overlayView = null
        updateOverlayStatus("off")
    }

    // ── Heartbeat — auto restore overlay jika hilang ─────────────
    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG_OVERLAY, "heartbeat: started")
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (overlayView == null && Settings.canDrawOverlays(context)) {
                    Log.w(TAG_OVERLAY, "heartbeat: overlay missing — restoring")
                    showOverlay(context)
                }
                // Update visibility check
                overlayView?.let { v ->
                    if (v.visibility != View.VISIBLE) {
                        v.visibility = View.VISIBLE
                        Log.w(TAG_OVERLAY, "heartbeat: forced visibility VISIBLE")
                    }
                }
            }
        }
        Log.d(TAG_OVERLAY, "heartbeat: job active=${heartbeatJob?.isActive}")
    }

    private fun updateOverlayStatus(status: String) {
        FpsService.setOverlayStatus(status)
        _uiState.value = _uiState.value.copy(
            debug = _uiState.value.debug.copy(overlayStatus = status)
        )
    }
}
