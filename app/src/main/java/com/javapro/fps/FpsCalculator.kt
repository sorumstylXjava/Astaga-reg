package com.javapro.fps

import kotlin.math.abs
import kotlin.math.sqrt

object FpsCalculator {

    fun calculate(frames: List<FrameSample>, refreshRateHz: Float): FpsStats {
        if (frames.size < 2) return FpsStats()

        val frameBudgetMs = 1000f / refreshRateHz
        val jankThreshold = frameBudgetMs * 1.5f
        val bigJankThreshold = frameBudgetMs * 3f

        val frameTimes = frames.map { it.frameTimeMs }.filter { it > 0f && it < 5000f }
        if (frameTimes.isEmpty()) return FpsStats()

        val totalTimeMs = frameTimes.sum()
        val frameCount = frameTimes.size

        val avgFps = if (totalTimeMs > 0f) frameCount * 1000f / totalTimeMs else 0f

        val perFrameFps = frameTimes.map { if (it > 0f) 1000f / it else 0f }
        val sorted = perFrameFps.sorted()

        val minFps = sorted.firstOrNull { it > 0f } ?: 0f
        val maxFps = sorted.lastOrNull() ?: 0f

        val mean = perFrameFps.average().toFloat()
        val variance = perFrameFps.map { (it - mean) * (it - mean) }.average().toFloat()

        val jankCount = frameTimes.count { it > jankThreshold }
        val bigJankCount = frameTimes.count { it > bigJankThreshold }
        val smoothFrames = frameTimes.count { it <= jankThreshold }
        val smoothness = if (frameCount > 0) smoothFrames * 100f / frameCount else 100f

        val idx1Low = (frameCount * 0.01f).toInt().coerceAtLeast(1)
        val idx5Low = (frameCount * 0.05f).toInt().coerceAtLeast(1)
        val fps1Low = sorted.take(idx1Low).average().toFloat().takeIf { !it.isNaN() } ?: 0f
        val fps5Low = sorted.take(idx5Low).average().toFloat().takeIf { !it.isNaN() } ?: 0f

        val currentFps = perFrameFps.takeLast(10).average().toFloat().takeIf { !it.isNaN() } ?: avgFps
        val avgFrameTime = frameTimes.average().toFloat()
        val maxFrameTime = frameTimes.maxOrNull() ?: 0f

        return FpsStats(
            currentFps = currentFps.coerceIn(0f, refreshRateHz * 1.5f),
            avgFps = avgFps.coerceIn(0f, refreshRateHz * 1.5f),
            minFps = minFps,
            maxFps = maxFps,
            frameTimeMs = avgFrameTime,
            maxFrameTimeMs = maxFrameTime,
            variance = variance,
            smoothness = smoothness.coerceIn(0f, 100f),
            fps1Low = fps1Low,
            fps5Low = fps5Low,
            jankCount = jankCount,
            bigJankCount = bigJankCount,
            totalFrames = frameCount
        )
    }
}
