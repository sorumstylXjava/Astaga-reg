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

object FpsMonitorManager : LifecycleOwner, SavedStateRegistryOwner {

    private const val TAG          = "FpsStatsVM"
    private const val TAG_OVERLAY  = "FpsOverlay"
    private const val POLL_MS      = 500L
    private const val RESOLVE_MS   = 1_000L   // resolver lebih lambat dari poll — tidak freeze
    private const val HEARTBEAT_MS = 5_000L

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
        Log.d(TAG, "FpsMonitorManager: initialized")
    }

    private val _uiState = MutableStateFlow(FpsUiState())
    val uiState: StateFlow<FpsUiState> = _uiState.asStateFlow()

    val isMonitoring: Boolean    get() = pollJob?.isActive == true
    val isOverlayVisible: Boolean get() = overlayView != null

    var overlayStatus: String = "off"
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var pollJob        : Job? = null
    private var heartbeatJob   : Job? = null

    private var windowManager  : WindowManager? = null
    private var overlayView    : ComposeView?   = null
    private val overlayState   = MutableStateFlow(FpsUiState())

    private var monitor  : FpsMonitor?         = null
    private var resolver : AutoTargetResolver? = null

    // Shared resolved target antara resolver loop dan poll loop
    @Volatile private var currentTarget = ResolvedTarget.empty()

    // ── Start monitoring ──────────────────────────────────────────
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
            targetPackage = "detecting…",
            refreshRateHz = hz,
            debug = DebugInfo(targetPackage = "detecting…", activeBackend = FpsBackend.NONE, overlayStatus = overlayStatus)
        )

        /**
         * Dua coroutine terpisah:
         * 1. resolverJob — update currentTarget tiap RESOLVE_MS (lebih lambat, aman)
         * 2. pollJob     — baca FPS tiap POLL_MS menggunakan currentTarget terkini
         *
         * Ini mencegah freeze karena dumpsys SurfaceFlinger --list tidak
         * dipanggil setiap 500ms — hanya tiap 1 detik.
         */

        // Resolver loop — independent dari poll loop
        val resolverJob = scope.launch {
            Log.d(TAG, "RESOLVER: loop started")
            var tick = 0
            while (isActive) {
                try {
                    val target = res.resolve()
                    currentTarget = target
                    tick++
                    if (tick == 1 || tick % 5 == 0) {
                        Log.d(TAG, "RESOLVER: #$tick target=${target.pkg} surface=${target.primarySurface()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: resolver tick=$tick", e)
                }
                delay(RESOLVE_MS)
            }
            Log.w(TAG, "RESOLVER: loop exited")
        }

        // Poll loop — gunakan currentTarget dari resolver
        pollJob = scope.launch {
            Log.d(TAG, "MONITOR_TICK: poll loop started hz=$hz")
            var tick = 0
            while (isActive) {
                try {
                    val target = currentTarget

                    if (!target.isValid()) {
                        // Idle — update overlay tapi tidak poll FPS
                        val idleState = _uiState.value.copy(
                            targetPackage = "waiting for app…",
                            debug = _uiState.value.debug.copy(targetPackage = "waiting for app…")
                        )
                        _uiState.value  = idleState
                        overlayState.value = idleState
                        delay(POLL_MS)
                        continue
                    }

                    val state = mon.poll(target, hz)
                    val enriched = state.copy(
                        targetPackage = target.pkg,
                        debug = state.debug.copy(
                            targetPackage = target.pkg,
                            overlayStatus = overlayStatus
                        )
                    )

                    _uiState.value     = enriched
                    overlayState.value = enriched
                    FpsSessionCache.updateFromState(enriched)
                    FpsSessionCache.currentPackage = target.pkg

                    tick++
                    if (tick == 1 || tick % 10 == 0) {
                        Log.d(TAG, "MONITOR_TICK: #$tick target=${target.pkg} " +
                            "fps=${state.fps.currentFps} backend=${state.activeBackend} " +
                            "frames=${state.debug.parsedFrameCount} fail='${state.debug.backendFailReason}'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: poll tick=$tick", e)
                    _uiState.value = _uiState.value.copy(
                        debug = _uiState.value.debug.copy(backendFailReason = "ex:${e.message?.take(60)}")
                    )
                }
                delay(POLL_MS)
            }
            Log.w(TAG, "UPDATE_LOOP_STOPPED: poll exited tick=$tick")
            resolverJob.cancel()
            FpsSessionCache.monitorRunning = false
        }

        Log.d(TAG, "startMonitoring: jobs launched poll=${pollJob?.isActive}")
    }

    fun startMonitoring(context: Context, @Suppress("UNUSED_PARAMETER") pkg: String) {
        startMonitoring(context)  // pkg ignored — auto detect
    }

    fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring: isRunning=$isMonitoring")
        pollJob?.cancel()
        pollJob = null
        monitor?.reset()
        monitor = null
        resolver?.clearCache()
        resolver = null
        currentTarget = ResolvedTarget.empty()
        FpsSessionCache.monitorRunning = false
        FpsSessionCache.currentPackage = ""
        _uiState.value = FpsUiState(isMonitoring = false)
        Log.d(TAG, "stopMonitoring: done")
    }

    // ── Overlay ───────────────────────────────────────────────────
    fun showOverlay(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showOverlay(context) }
            return
        }

        Log.d(TAG_OVERLAY, "showOverlay: called isVisible=$isOverlayVisible")

        if (!Settings.canDrawOverlays(context)) {
            setOverlayStatus("no_permission")
            Log.w(TAG_OVERLAY, "OVERLAY_FAILED: SYSTEM_ALERT_WINDOW not granted")
            return
        }

        if (overlayView != null) {
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: already active")
            return
        }

        if (windowManager == null) {
            windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        val wm   = windowManager!!
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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 80 }

        Log.d(TAG_OVERLAY, "showOverlay: creating ComposeView type=$type")

        val view = ComposeView(context.applicationContext).apply {
            setViewTreeLifecycleOwner(this@FpsMonitorManager)
            setViewTreeSavedStateRegistryOwner(this@FpsMonitorManager)
            visibility = View.VISIBLE
            setContent {
                val s = overlayState.collectAsState()
                // ── DEBUG: background merah saat testing ─────────────
                // Ubah Color(0xCC000000) → Color(0xFFFF0000) untuk debug
                // Setelah overlay confirmed muncul, uncomment FpsBubble di bawah
                // ─────────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .background(Color(0xCC000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fps = s.value.fps.currentFps
                    val pkg = s.value.targetPackage.take(20)
                    Text(
                        text = if (fps > 0f) "%.0f FPS".format(fps) else "-- FPS | $pkg",
                        color = when {
                            fps > 0f  -> Color(0xFF81C784)
                            else      -> Color(0xFF90A4AE)
                        },
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                // ── Uncomment setelah overlay confirmed muncul ───────
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
            setOverlayStatus("error:${e.message?.take(40)}")
            Log.e(TAG_OVERLAY, "OVERLAY_FAILED: addView EXCEPTION", e)
        }
    }

    fun hideOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideOverlay() }
            return
        }
        Log.d(TAG_OVERLAY, "hideOverlay: isVisible=$isOverlayVisible")
        heartbeatJob?.cancel()
        heartbeatJob = null
        overlayView?.let { v ->
            try { windowManager?.removeViewImmediate(v); Log.d(TAG_OVERLAY, "hideOverlay: ok") }
            catch (e: Exception) { Log.e(TAG_OVERLAY, "OVERLAY_FAILED: removeView", e) }
        }
        overlayView = null
        FpsSessionCache.overlayVisible = false
        setOverlayStatus("off")
    }

    private fun startHeartbeat(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG_OVERLAY, "heartbeat: started ${HEARTBEAT_MS}ms")
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (overlayView == null && FpsSessionCache.overlayVisible) {
                    Log.w(TAG_OVERLAY, "heartbeat: overlay gone — restoring")
                    showOverlay(context)
                }
                overlayView?.let { v ->
                    if (v.visibility != View.VISIBLE) {
                        v.visibility = View.VISIBLE
                        Log.w(TAG_OVERLAY, "heartbeat: forced VISIBLE")
                    }
                }
            }
        }
    }

    fun setOverlayStatus(status: String) {
        overlayStatus = status
        Log.d(TAG_OVERLAY, "overlayStatus=$status")
        try { FpsService.setOverlayStatus(status) } catch (_: Exception) {}
        _uiState.value = _uiState.value.copy(
            debug = _uiState.value.debug.copy(overlayStatus = status)
        )
    }

    fun reconnect(): FpsUiState {
        Log.d(TAG, "reconnect: isMonitoring=$isMonitoring pkg=${FpsSessionCache.currentPackage}")
        return _uiState.value
    }
}
