package com.javapro.fps

import android.content.Context
import android.os.Build
import android.view.WindowManager

object RefreshRateDetector {

    fun detect(context: Context): Float {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val mode = wm.currentWindowMetrics
                // use display API for R+
                val display = wm.defaultDisplay
                @Suppress("DEPRECATION")
                val rate = display.refreshRate
                rate.takeIf { it > 0f } ?: 60f
            } else {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val rate = wm.defaultDisplay.refreshRate
                rate.takeIf { it > 0f } ?: 60f
            }
        } catch (e: Exception) {
            60f
        }
    }

    fun frameBudgetMs(refreshHz: Float): Float = 1000f / refreshHz.coerceAtLeast(1f)

    fun jankThresholdMs(refreshHz: Float): Float = frameBudgetMs(refreshHz) * 1.5f

    fun bigJankThresholdMs(refreshHz: Float): Float = frameBudgetMs(refreshHz) * 3f
}
