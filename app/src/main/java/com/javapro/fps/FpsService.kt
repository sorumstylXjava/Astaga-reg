package com.javapro.fps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * FpsService — foreground service wrapper.
 *
 * Tanggung jawab:
 * - Jalankan foreground service agar Android tidak kill proses saat background
 * - Delegate monitoring + overlay ke FpsMonitorManager
 *
 * FpsMonitorManager sudah handle semua logic.
 * Service ini hanya berfungsi sebagai "process keep-alive" via foreground notification.
 *
 * Start:
 *   val i = Intent(context, FpsService::class.java)
 *   i.putExtra(EXTRA_PACKAGE, "com.example.game")
 *   i.putExtra(EXTRA_SHOW_OVERLAY, true)
 *   context.startForegroundService(i)
 */
class FpsService : Service() {

    companion object {
        private const val TAG        = "FpsService"
        private const val CHANNEL_ID = "fps_monitor_channel"
        private const val NOTIF_ID   = 9001
        const val EXTRA_PACKAGE      = "package"
        const val EXTRA_SHOW_OVERLAY = "show_overlay"

        // Backward-compat state relay dari FpsMonitorManager
        val state: StateFlow<FpsUiState> get() = FpsMonitorManager.uiState

        val overlayStatus: String get() = FpsMonitorManager.overlayStatus

        fun setOverlayStatus(status: String) {
            // Sudah handle di FpsMonitorManager.setOverlayStatus()
            Log.d(TAG, "setOverlayStatus: $status")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FpsService.onCreate running=${FpsMonitorManager.isMonitoring}")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg         = intent?.getStringExtra(EXTRA_PACKAGE)
        val showOverlay = intent?.getBooleanExtra(EXTRA_SHOW_OVERLAY, false) ?: false

        Log.d(TAG, "onStartCommand: pkg=$pkg overlay=$showOverlay")

        startForeground(NOTIF_ID, buildNotification())
        Log.d(TAG, "onStartCommand: foreground started")

        if (pkg.isNullOrBlank()) {
            Log.w(TAG, "onStartCommand: no package — service will only keep process alive")
        } else {
            FpsMonitorManager.startMonitoring(applicationContext, pkg)
        }

        if (showOverlay) {
            // showOverlay handles Main thread internally
            FpsMonitorManager.showOverlay(applicationContext)
        }

        Log.d(TAG, "onStartCommand: done isMonitoring=${FpsMonitorManager.isMonitoring} " +
            "overlayVisible=${FpsMonitorManager.isOverlayVisible}")

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "FpsService.onDestroy")
        // Jangan stop monitoring — biarkan Manager tetap jalan
        // Overlay tetap visible via WindowManager
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentText("Monitoring FPS…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
}
