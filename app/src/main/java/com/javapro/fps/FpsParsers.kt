package com.javapro.fps

object FramestatsParser {

    private const val NS_TO_MS = 1_000_000f
    private const val HEADER_TOKEN = "---PROFILEDATA---"

    fun parse(raw: String): List<FrameSample> {
        val lines = raw.lines()
        val result = mutableListOf<FrameSample>()

        val startIdx = lines.indexOfFirst { it.contains(HEADER_TOKEN) }
        val dataLines = if (startIdx >= 0) lines.drop(startIdx + 2) else lines

        var lastTs = 0L
        for (line in dataLines) {
            val cols = line.trim().split(",")
            if (cols.size < 13) continue
            val flags = cols[0].trim().toLongOrNull() ?: continue
            if (flags != 0L) continue

            val vsync = cols[1].trim().toLongOrNull() ?: continue
            val swapEnd = cols[12].trim().toLongOrNull() ?: continue

            if (vsync <= 0L || swapEnd <= 0L) continue
            if (vsync == lastTs) continue
            if (swapEnd < vsync) continue

            val frameTimeMs = (swapEnd - vsync) / NS_TO_MS
            if (frameTimeMs <= 0f || frameTimeMs > 5000f) continue

            result.add(FrameSample(timestamp = vsync, frameTimeMs = frameTimeMs))
            lastTs = vsync
        }
        return result
    }

    fun isValid(raw: String): Boolean =
        raw.contains(HEADER_TOKEN) || raw.lines().any { it.trim().split(",").size >= 13 }
}

object SurfaceFlingerParser {

    private const val NS_TO_MS = 1_000_000f

    fun parse(raw: String): List<FrameSample> {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        val refreshPeriodNs = lines[0].trim().toLongOrNull() ?: return emptyList()
        if (refreshPeriodNs <= 0L) return emptyList()

        val timestamps = mutableListOf<Long>()
        for (line in lines.drop(1)) {
            val cols = line.trim().split("\\s+".toRegex())
            if (cols.size < 3) continue
            val desired = cols[0].toLongOrNull() ?: continue
            val actual  = cols[1].toLongOrNull() ?: continue
            if (desired <= 0L || actual <= 0L) continue
            timestamps.add(actual)
        }

        if (timestamps.size < 2) return emptyList()

        val result = mutableListOf<FrameSample>()
        for (i in 1 until timestamps.size) {
            val diff = timestamps[i] - timestamps[i - 1]
            if (diff <= 0L) continue
            val frameTimeMs = diff / NS_TO_MS
            if (frameTimeMs <= 0f || frameTimeMs > 5000f) continue
            result.add(FrameSample(timestamp = timestamps[i], frameTimeMs = frameTimeMs))
        }
        return result
    }

    fun isOnlyRefreshRate(raw: String): Boolean {
        val lines = raw.lines().filter { it.isNotBlank() }
        return lines.size == 1 && lines[0].trim().toLongOrNull() != null
    }

    fun isValid(raw: String): Boolean {
        if (isOnlyRefreshRate(raw)) return false
        val lines = raw.lines().filter { it.isNotBlank() }
        return lines.size >= 3
    }
}
