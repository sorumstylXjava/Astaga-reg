package com.javapro.fps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Background service for FPS monitoring.
 * Nama class: FpsService — match dengan entry .FpsService di AndroidManifest.xml.
 * foregroundServiceType="specialUse" sudah ada di Manifest, tidak perlu tambah.
 *
 * Start:
 *   val intent = Intent(context, FpsService::class.java)
 *   intent.putExtra(FpsService.EXTRA_PACKAGE, "com.example.game")
 *   context.startForegroundService(intent)
 *
 * Observe:
 *   FpsService.state.collect { uiState -> ... }
 */
class FpsService : Service() {

    companion object {
        private const val CHANNEL_ID = "fps_monitor_channel"
        private const val NOTIF_ID   = 9001
        const val EXTRA_PACKAGE      = "package"

        val state: StateFlow<FpsUiState> get() = _state
        private val _state = MutableStateFlow(FpsUiState())
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
        startForeground(NOTIF_ID, buildNotification())

        val executor = TweakShellExecutor(applicationContext)
        val refreshHz = RefreshRateDetector.detect(applicationContext)
        val monitor   = FpsMonitor(executor)

        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val newState = monitor.poll(pkg, refreshHz)
                _state.value = newState
                delay(500L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
