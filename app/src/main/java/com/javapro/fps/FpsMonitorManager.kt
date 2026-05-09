package com.javapro.fps

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * FpsMonitorManager — SINGLETON GLOBAL.
 *
 * - Tidak terikat lifecycle screen / Activity / ViewModel
 * - Monitoring survive: navigate away, reopen, background, game launch
 * - Overlay di-manage di sini via WindowManager
 * - Screen hanya observer (collect StateFlow)
 *
 * Overlay architecture:
 *   ComposeView → WindowManager.addView() langsung
 *   Bukan dari Activity / Fragment / NavHost
 *   Survive semua navigation event
 */
object FpsMonitorManager : LifecycleOwner, SavedStateRegistryOwner {

    private const val TAG          = "FpsStatsVM"
    private const val TAG_OVERLAY  = "FpsOverlay"
    private const val TAG_SERVICE  = "FpsService"
    private const val POLL_MS      = 500L
    private const val HEARTBEAT_MS = 5_000L

    // ── Lifecycle owner untuk ComposeView overlay ─────────────────
    private val lifecycleRegistry            = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle        get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Log.d(TAG, "FpsMonitorManager: object initialized")
    }

    // ── State ──────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(FpsUiState())
    val uiState: StateFlow<FpsUiState> = _uiState.asStateFlow()

    val isMonitoring: Boolean    get() = pollJob?.isActive == true
    val isOverlayVisible: Boolean get() = overlayView != null

    // overlayStatus — dibaca dari FpsService.overlayStatus untuk backward compat
    var overlayStatus: String = "off"
        private set

    // ── Internal ───────────────────────────────────────────────────
    // SupervisorJob — satu child crash tidak kill semua
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var pollJob     : Job? = null
    private var heartbeatJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView  : ComposeView?   = null
    private var overlayStateFlow = MutableStateFlow(FpsUiState())

    private var monitor : FpsMonitor?          = null
    private var resolver: AutoTargetResolver?  = null

    // ── Start monitoring — AUTO TARGET, tidak perlu package manual ─
    fun startMonitoring(context: Context) {
        Log.d(TAG, "startMonitoring: isRunning=$isMonitoring")

        if (pollJob?.isActive == true) {
            Log.d(TAG, "startMonitoring: already running, skip")
            return
        }

        FpsSessionCache.monitorRunning = true

        val hz   = RefreshRateDetector.detect(context)
        val exec = TweakShellExecutor(context)
        val mon  = FpsMonitor(exec)
        val res  = AutoTargetResolver(exec)
        mon.reset()
        res.clearCache()
        monitor  = mon
        resolver = res

        _uiState.value = FpsUiState(
            isMonitoring  = true,
            targetPackage = "auto-detecting…",
            refreshRateHz = hz,
            debug = DebugInfo(
                targetPackage = "auto-detecting…",
                activeBackend = FpsBackend.NONE,
                overlayStatus = overlayStatus
            )
        )
        Log.d(TAG, "startMonitoring: launching auto-resolver + poll loop hz=$hz")

        pollJob = scope.launch {
            Log.d(TAG, "MONITOR_TICK: coroutine started (auto-target mode)")
            var tick = 0
            while (isActive) {
                try {
                    // ── Step 1: Resolve foreground target setiap tick ──
                    val target = res.resolve()

                    if (!target.isValid()) {
                        Log.d(TAG, "MONITOR_TICK: #$tick target invalid/empty, skip poll")
                        delay(POLL_MS)
                        continue
                    }

                    // ── Step 2: Poll FPS dengan target terkini ─────────
                    val state = mon.poll(target, hz)

                    // Inject target info ke state
                    val stateWithTarget = state.copy(
                        targetPackage = target.pkg,
                        debug = state.debug.copy(
                            targetPackage = target.pkg,
                            overlayStatus = overlayStatus
                        )
                    )

                    _uiState.value         = stateWithTarget
                    overlayStateFlow.value = stateWithTarget
                    FpsSessionCache.updateFromState(stateWithTarget)
                    FpsSessionCache.currentPackage = target.pkg

                    tick++
                    if (tick == 1 || tick % 10 == 0) {
                        Log.d(TAG, "MONITOR_TICK: #$tick " +
                            "target=${target.pkg} " +
                            "surface=${target.primarySurface()} " +
                            "fps=${state.fps.currentFps} " +
                            "backend=${state.activeBackend} " +
                            "frames=${state.debug.parsedFrameCount}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: poll tick=$tick", e)
                    _uiState.value = _uiState.value.copy(
                        debug = _uiState.value.debug.copy(
                            backendFailReason = "poll_ex:${e.message?.take(60)}"
                        )
                    )
                }
                delay(POLL_MS)
            }
            Log.w(TAG, "UPDATE_LOOP_STOPPED: exited tick=$tick isActive=$isActive")
            FpsSessionCache.monitorRunning = false
        }

        Log.d(TAG, "startMonitoring: pollJob launched active=${pollJob?.isActive}")
    }

    // ── Overload untuk backward compat — ignored targetPackage, langsung auto ─
    fun startMonitoring(context: Context, @Suppress("UNUSED_PARAMETER") targetPackage: String) {
        Log.d(TAG, "startMonitoring(pkg): legacy call, redirecting to auto-target mode")
        startMonitoring(context)
    }

    fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring: called isRunning=$isMonitoring")
        pollJob?.cancel()
        pollJob = null
        monitor?.reset()
        monitor = null
        resolver?.clearCache()
        resolver = null
        FpsSessionCache.monitorRunning = false
        FpsSessionCache.currentPackage = ""
        _uiState.value = FpsUiState(isMonitoring = false)
        Log.d(TAG, "stopMonitoring: done")
    }

    // ── Overlay — WAJIB dipanggil dari Main thread ────────────────
    fun showOverlay(context: Context) {
        // Pastikan selalu di Main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showOverlay(context) }
            return
        }

        Log.d(TAG_OVERLAY, "showOverlay: called isVisible=$isOverlayVisible")

        if (!Settings.canDrawOverlays(context)) {
            setOverlayStatus("no_permission")
            Log.w(TAG_OVERLAY, "OVERLAY_FAILED: permission SYSTEM_ALERT_WINDOW not granted")
            return
        }

        if (overlayView != null) {
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: already active, skip addView")
            return
        }

        if (windowManager == null) {
            windowManager = context.applicationContext
                .getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        val view = ComposeView(context.applicationContext).apply {
            setViewTreeLifecycleOwner(this@FpsMonitorManager)
            setViewTreeSavedStateRegistryOwner(this@FpsMonitorManager)
            visibility = View.VISIBLE
            setContent {
                val s = overlayStateFlow.collectAsState()
                // ──────────────────────────────────────────────────────
                // DEBUG OVERLAY — background merah agar visible saat test
                // Setelah confirmed muncul, ganti dengan FpsBubble
                // ──────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .background(Color(0xCC000000))  // ubah ke 0xFFFF0000 saat debug
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fps = s.value.fps.currentFps
                    Text(
                        text = if (fps > 0f) "%.0f FPS".format(fps) else "-- FPS",
                        color = if (fps > 0f) Color(0xFF81C784) else Color(0xFF90A4AE),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                // ── Uncomment ini setelah overlay confirmed working ──
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
            setOverlayStatus("active")
            FpsSessionCache.overlayVisible = true
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: addView SUCCESS type=$type")
            startHeartbeat(context)
        } catch (e: Exception) {
            setOverlayStatus("addView_error:${e.message?.take(50)}")
            Log.e(TAG_OVERLAY, "OVERLAY_FAILED: addView EXCEPTION", e)
        }
    }

    fun hideOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideOverlay() }
            return
        }
        Log.d(TAG_OVERLAY, "hideOverlay: called isVisible=$isOverlayVisible")
        heartbeatJob?.cancel()
        heartbeatJob = null
        overlayView?.let { v ->
            try {
                windowManager?.removeViewImmediate(v)
                Log.d(TAG_OVERLAY, "hideOverlay: removed ok")
            } catch (e: Exception) {
                Log.e(TAG_OVERLAY, "OVERLAY_FAILED: removeView exception", e)
            }
        }
        overlayView = null
        FpsSessionCache.overlayVisible = false
        setOverlayStatus("off")
    }

    // ── Heartbeat — auto restore overlay jika hilang ──────────────
    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG_OVERLAY, "heartbeat: started interval=${HEARTBEAT_MS}ms")
            while (isActive) {
                delay(HEARTBEAT_MS)
                // Cek overlay masih ada
                if (overlayView == null && FpsSessionCache.overlayVisible) {
                    Log.w(TAG_OVERLAY, "heartbeat: overlay missing — restoring")
                    showOverlay(context)
                    Log.d(TAG_OVERLAY, "heartbeat: restore attempted visible=${overlayView != null}")
                }
                // Force visibility jika ada tapi hidden
                overlayView?.let { v ->
                    if (v.visibility != View.VISIBLE) {
                        v.visibility = View.VISIBLE
                        Log.w(TAG_OVERLAY, "heartbeat: forced VISIBLE")
                    }
                }
            }
        }
        Log.d(TAG_OVERLAY, "heartbeat: job started active=${heartbeatJob?.isActive}")
    }

    fun setOverlayStatus(status: String) {
        overlayStatus = status
        Log.d(TAG_OVERLAY, "overlayStatus=$status")
        // Sync ke FpsService compat field
        try { FpsService.setOverlayStatus(status) } catch (_: Exception) {}
        _uiState.value = _uiState.value.copy(
            debug = _uiState.value.debug.copy(overlayStatus = status)
        )
    }

    /**
     * Dipanggil saat screen dibuka kembali.
     * Restore UI state dari cache jika monitoring masih jalan.
     */
    fun reconnect(): FpsUiState {
        Log.d(TAG, "reconnect: isMonitoring=$isMonitoring " +
            "pkg=${FpsSessionCache.currentPackage} " +
            "lastFps=${FpsSessionCache.lastGoodFps}")
        return _uiState.value
    }
}
