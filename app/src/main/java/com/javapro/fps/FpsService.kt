package com.javapro.fps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
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

class FpsService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG            = "FpsService"
        private const val TAG_OVERLAY    = "FpsOverlay"
        private const val CHANNEL_ID     = "fps_monitor_channel"
        private const val NOTIF_ID       = 9001
        const val EXTRA_PACKAGE          = "package"
        const val EXTRA_SHOW_OVERLAY     = "show_overlay"

        // Shared state untuk UI screen
        val state: StateFlow<FpsUiState> get() = _state
        private val _state = MutableStateFlow(FpsUiState())

        var overlayStatus: String = "off"
            private set

        // Dipanggil dari FpsMonitorManager
        fun setOverlayStatus(status: String) {
            overlayStatus = status
            Log.d("FpsOverlay", "overlayStatus=$status")
        }
    }

    // ── Lifecycle untuk ComposeView ─────────────────────────────
    private val lifecycleRegistry            = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle        get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ── Coroutine scope — SupervisorJob agar tidak mati jika satu child crash ──
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob : Job? = null
    private var overlayHeartbeatJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView?     = null

    // StateFlow untuk overlay — di-collect oleh ComposeView
    private val overlayStateFlow = MutableStateFlow(FpsUiState())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FpsService.onCreate")
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg         = intent?.getStringExtra(EXTRA_PACKAGE)
        val showOverlay = intent?.getBooleanExtra(EXTRA_SHOW_OVERLAY, false) ?: false

        Log.d(TAG, "onStartCommand: pkg=$pkg overlay=$showOverlay intent=${intent?.action}")

        if (pkg.isNullOrBlank()) {
            Log.w(TAG, "onStartCommand: empty package, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        if (showOverlay) {
            Log.d(TAG_OVERLAY, "onStartCommand: overlay requested")
            CoroutineScope(Dispatchers.Main).launch {
                attachOverlay()
                startOverlayHeartbeat()
            }
        }

        val executor  = TweakShellExecutor(applicationContext)
        val refreshHz = RefreshRateDetector.detect(applicationContext)
        val monitor   = FpsMonitor(executor)

        Log.d(TAG, "onStartCommand: launching poll loop hz=$refreshHz")
        pollJob?.cancel()
        pollJob = scope.launch {
            Log.d(TAG, "MONITOR_TICK: service poll loop started pkg=$pkg")
            var tick = 0
            while (isActive) {
                try {
                    val s = monitor.poll(pkg, refreshHz)
                    _state.value            = s
                    overlayStateFlow.value  = s
                    tick++
                    if (tick == 1 || tick % 20 == 0) {
                        Log.d(TAG, "MONITOR_TICK: #$tick fps=${s.fps.currentFps} backend=${s.activeBackend}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: service poll error tick=$tick", e)
                }
                delay(500L)
            }
            Log.w(TAG, "UPDATE_LOOP_STOPPED: service loop exited tick=$tick isActive=$isActive")
        }

        Log.d(TAG, "onStartCommand: pollJob active=${pollJob?.isActive}")
        return START_STICKY  // Android restart service jika mati
    }

    override fun onDestroy() {
        Log.d(TAG, "FpsService.onDestroy")
        pollJob?.cancel()
        overlayHeartbeatJob?.cancel()
        scope.cancel()
        CoroutineScope(Dispatchers.Main).launch { detachOverlay() }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        overlayStatus = "off"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Overlay ─────────────────────────────────────────────────
    private fun attachOverlay() {
        Log.d(TAG_OVERLAY, "attachOverlay: checking permission")

        if (!Settings.canDrawOverlays(this)) {
            overlayStatus = "no_permission"
            Log.w(TAG_OVERLAY, "OVERLAY_FAILED: SYSTEM_ALERT_WINDOW not granted")
            return
        }

        if (overlayView != null) {
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: already active, skip")
            return
        }

        val wm = windowManager ?: run {
            overlayStatus = "error: wm_null"
            Log.e(TAG_OVERLAY, "OVERLAY_FAILED: WindowManager is null")
            return
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        Log.d(TAG_OVERLAY, "attachOverlay: type=$type")

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 80
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FpsService)
            setViewTreeSavedStateRegistryOwner(this@FpsService)
            setContent {
                val s = overlayStateFlow.collectAsState()
                com.javapro.fps.ui.FpsBubble(
                    fps           = s.value.fps.currentFps,
                    refreshRateHz = s.value.refreshRateHz,
                    frameTimeMs   = s.value.fps.frameTimeMs
                )
            }
        }

        try {
            wm.addView(view, params)
            overlayView   = view
            overlayStatus = "active"
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: addView success type=$type")
        } catch (e: Exception) {
            overlayStatus = "error: ${e.message?.take(60)}"
            Log.e(TAG_OVERLAY, "OVERLAY_FAILED: addView exception", e)
        }
    }

    private fun detachOverlay() {
        if (overlayView == null) return
        try {
            windowManager?.removeViewImmediate(overlayView!!)
            Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: overlay removed ok")
        } catch (e: Exception) {
            Log.w(TAG_OVERLAY, "OVERLAY_FAILED: removeView error ${e.message}")
        }
        overlayView   = null
        overlayStatus = "off"
    }

    // ── Overlay heartbeat — pastikan overlay masih visible ───────
    private fun startOverlayHeartbeat() {
        overlayHeartbeatJob?.cancel()
        overlayHeartbeatJob = CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG_OVERLAY, "heartbeat: started")
            while (isActive) {
                delay(5_000L)  // cek tiap 5 detik
                if (overlayView == null && Settings.canDrawOverlays(this@FpsService)) {
                    Log.w(TAG_OVERLAY, "heartbeat: overlay missing — restoring")
                    attachOverlay()
                    Log.d(TAG_OVERLAY, "OVERLAY_ATTACHED: overlay restored by heartbeat")
                }
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FPS Monitor", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Realtime FPS monitoring" }
            )
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FPS Monitor")
            .setContentText("Monitoring realtime…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
}
