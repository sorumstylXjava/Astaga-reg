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
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/**
 * FpsService — background FPS monitor + optional overlay.
 *
 * Cocok dengan entry .FpsService di AndroidManifest.xml.
 * foregroundServiceType="specialUse" sudah ada di Manifest.
 *
 * Start monitor saja (tanpa overlay):
 *   intent.putExtra(FpsService.EXTRA_PACKAGE, "com.example.game")
 *
 * Start dengan overlay:
 *   intent.putExtra(FpsService.EXTRA_PACKAGE, "com.example.game")
 *   intent.putExtra(FpsService.EXTRA_SHOW_OVERLAY, true)
 *
 * Syarat overlay: Settings.canDrawOverlays(context) == true
 * Jika tidak, overlay di-skip dan overlayStatus = "no_permission"
 */
class FpsService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    // ─── Lifecycle untuk ComposeView overlay ───────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    companion object {
        private const val CHANNEL_ID    = "fps_monitor_channel"
        private const val NOTIF_ID      = 9001
        const val EXTRA_PACKAGE         = "package"
        const val EXTRA_SHOW_OVERLAY    = "show_overlay"

        val state: StateFlow<FpsUiState> get() = _state
        private val _state = MutableStateFlow(FpsUiState())

        var overlayStatus: String = "off"
            private set
    }

    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null

    // State untuk overlay bubble — diupdate dari coroutine
    private var overlayFps by mutableFloatStateOf(0f)
    private var overlayFrameTime by mutableFloatStateOf(0f)
    private var overlayRefreshHz by mutableFloatStateOf(60f)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg         = intent?.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
        val showOverlay = intent.getBooleanExtra(EXTRA_SHOW_OVERLAY, false)

        startForeground(NOTIF_ID, buildNotification())
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val executor  = TweakShellExecutor(applicationContext)
        val refreshHz = RefreshRateDetector.detect(applicationContext)
        val monitor   = FpsMonitor(executor)

        if (showOverlay) attachOverlay(refreshHz)

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val newState = monitor.poll(pkg, refreshHz)
                _state.value = newState

                // Update overlay state values (safe dari coroutine IO)
                withContext(Dispatchers.Main) {
                    overlayFps       = newState.fps.currentFps
                    overlayFrameTime = newState.fps.frameTimeMs
                    overlayRefreshHz = refreshHz
                }

                delay(500L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        detachOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay attach / detach ───────────────────────────────
    private fun attachOverlay(refreshHz: Float) {
        if (!Settings.canDrawOverlays(this)) {
            overlayStatus = "no_permission"
            return
        }
        if (overlayView != null) return  // sudah attached

        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 80
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FpsService)
            setViewTreeSavedStateRegistryOwner(this@FpsService)
            setContent {
                com.javapro.fps.ui.FpsBubble(
                    fps          = overlayFps,
                    refreshRateHz = refreshHz,
                    frameTimeMs  = overlayFrameTime
                )
            }
        }

        try {
            wm.addView(composeView, params)
            overlayView  = composeView
            overlayStatus = "active"
        } catch (e: Exception) {
            overlayStatus = "error: ${e.message}"
        }
    }

    private fun detachOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {}
            overlayView = null
        }
        overlayStatus = "off"
    }

    // ─── Notification ──────────────────────────────────────────
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "FPS Monitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Realtime FPS monitoring" }
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
