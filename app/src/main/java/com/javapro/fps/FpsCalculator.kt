package com.javapro.fps

object FpsCalculator {

    fun calculate(frames: List<FrameSample>, refreshRateHz: Float): FpsStats {
        if (frames.size < 2) return FpsStats()

        val frameBudgetMs    = 1000f / refreshRateHz.coerceAtLeast(1f)
        val jankThreshold    = frameBudgetMs * 1.5f
        val bigJankThreshold = frameBudgetMs * 3f

        val frameTimes = frames.map { it.frameTimeMs }.filter { it > 0f && it < 500f }
        if (frameTimes.isEmpty()) return FpsStats()

        val frameCount   = frameTimes.size
        val totalTimeMs  = frameTimes.sum()

        // AVG FPS = jumlah frame / total waktu
        val avgFps = if (totalTimeMs > 0f) frameCount * 1000f / totalTimeMs else 0f

        // Per-frame FPS list untuk statistik
        val perFrameFps = frameTimes.map { 1000f / it }
        val sorted      = perFrameFps.sorted()

        val minFps = sorted.firstOrNull { it > 0f } ?: 0f
        val maxFps = sorted.lastOrNull() ?: 0f

        val mean     = perFrameFps.average().toFloat()
        val variance = perFrameFps.map { (it - mean) * (it - mean) }.average().toFloat()

        val jankCount    = frameTimes.count { it > jankThreshold }
        val bigJankCount = frameTimes.count { it > bigJankThreshold }
        val smoothFrames = frameTimes.count { it <= jankThreshold }
        val smoothness   = smoothFrames * 100f / frameCount

        val idx1Low = (frameCount * 0.01f).toInt().coerceAtLeast(1)
        val idx5Low = (frameCount * 0.05f).toInt().coerceAtLeast(1)
        val fps1Low = sorted.take(idx1Low).average().toFloat().takeIf { !it.isNaN() } ?: 0f
        val fps5Low = sorted.take(idx5Low).average().toFloat().takeIf { !it.isNaN() } ?: 0f

        // CURRENT FPS — pakai 20 frame terakhir (lebih responsif dari seluruh history)
        val recentTimes = frameTimes.takeLast(20)
        val recentTotal = recentTimes.sum()
        val currentFps  = if (recentTotal > 0f) recentTimes.size * 1000f / recentTotal else avgFps

        val avgFrameTime = frameTimes.average().toFloat()
        val maxFrameTime = frameTimes.maxOrNull() ?: 0f

        return FpsStats(
            currentFps    = currentFps.coerceIn(0f, refreshRateHz * 1.5f),
            avgFps        = avgFps.coerceIn(0f, refreshRateHz * 1.5f),
            minFps        = minFps,
            maxFps        = maxFps,
            frameTimeMs   = avgFrameTime,
            maxFrameTimeMs = maxFrameTime,
            variance      = variance,
            smoothness    = smoothness.coerceIn(0f, 100f),
            fps1Low       = fps1Low,
            fps5Low       = fps5Low,
            jankCount     = jankCount,
            bigJankCount  = bigJankCount,
            totalFrames   = frameCount
        )
    }
}
