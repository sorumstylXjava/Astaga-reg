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
        private const val TAG        = "FpsService"
        private const val CHANNEL_ID = "fps_monitor_channel"
        private const val NOTIF_ID   = 9001
        const val EXTRA_PACKAGE      = "package"
        const val EXTRA_SHOW_OVERLAY = "show_overlay"

        val state: StateFlow<FpsUiState> get() = _state
        private val _state = MutableStateFlow(FpsUiState())

        var overlayStatus: String = "off"
            private set
    }

    private val lifecycleRegistry            = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle        get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView?     = null

    // StateFlow untuk overlay — Compose collect ini untuk recompose
    private val overlayState = MutableStateFlow(FpsUiState())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MONITOR_START: FpsService.onCreate")
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg         = intent?.getStringExtra(EXTRA_PACKAGE)
        val showOverlay = intent?.getBooleanExtra(EXTRA_SHOW_OVERLAY, false) ?: false
        Log.d(TAG, "MONITOR_START: pkg=$pkg overlay=$showOverlay")

        if (pkg.isNullOrBlank()) {
            Log.w(TAG, "MONITOR_START: empty package — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        if (showOverlay) {
            CoroutineScope(Dispatchers.Main).launch { attachOverlay() }
        }

        val executor  = TweakShellExecutor(applicationContext)
        val refreshHz = RefreshRateDetector.detect(applicationContext)
        val monitor   = FpsMonitor(executor)

        pollJob?.cancel()
        pollJob = scope.launch {
            Log.d(TAG, "MONITOR_TICK: loop start pkg=$pkg hz=$refreshHz")
            var tick = 0
            while (isActive) {
                try {
                    val s = monitor.poll(pkg, refreshHz)
                    _state.value       = s
                    overlayState.value = s
                    tick++
                    if (tick % 10 == 0)
                        Log.d(TAG, "MONITOR_TICK: #$tick fps=${s.fps.currentFps} backend=${s.activeBackend}")
                } catch (e: Exception) {
                    Log.e(TAG, "EXCEPTION_STACKTRACE: poll error", e)
                }
                delay(500L)
            }
            Log.w(TAG, "UPDATE_LOOP_STOPPED: poll loop exited isActive=$isActive")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "MONITOR_START: FpsService.onDestroy")
        pollJob?.cancel()
        scope.cancel()
        CoroutineScope(Dispatchers.Main).launch { detachOverlay() }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        overlayStatus = "off"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Overlay ─────────────────────────────────────────────────
    private fun attachOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            overlayStatus = "no_permission"
            Log.w(TAG, "OVERLAY_FAILED: SYSTEM_ALERT_WINDOW not granted")
            return
        }
        if (overlayView != null) {
            Log.d(TAG, "OVERLAY_ATTACHED: already active")
            return
        }

        val wm = windowManager ?: run {
            overlayStatus = "error: wm_null"
            Log.e(TAG, "OVERLAY_FAILED: WindowManager is null")
            return
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 16; y = 80 }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FpsService)
            setViewTreeSavedStateRegistryOwner(this@FpsService)
            setContent {
                val s = overlayState.collectAsState()
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
            Log.d(TAG, "OVERLAY_ATTACHED: addView success type=$type")
        } catch (e: Exception) {
            overlayStatus = "error: ${e.message?.take(60)}"
            Log.e(TAG, "OVERLAY_FAILED: addView exception", e)
        }
    }

    private fun detachOverlay() {
        overlayView?.let { v ->
            try {
                windowManager?.removeViewImmediate(v)
                Log.d(TAG, "OVERLAY_ATTACHED: removed ok")
            } catch (e: Exception) {
                Log.w(TAG, "OVERLAY_FAILED: removeView ${e.message}")
            }
            overlayView = null
        }
        overlayStatus = "off"
    }

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
            .setContentText("Monitoring performance…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
}
