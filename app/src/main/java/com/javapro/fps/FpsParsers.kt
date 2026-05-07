package com.javapro.fps

// ─────────────────────────────────────────────────────────────
// FramestatsParser
// Sumber: dumpsys gfxinfo <pkg> framestats
// Format: CSV, kolom 0=flags, 1=intended_vsync, 12=frame_completed
// ─────────────────────────────────────────────────────────────
object FramestatsParser {

    private const val NS_TO_MS = 1_000_000f
    private const val HEADER_TOKEN = "---PROFILEDATA---"

    fun parse(raw: String): List<FrameSample> {
        val lines = raw.lines()
        val result = mutableListOf<FrameSample>()

        // Cari section data setelah header. Jika tidak ada header, coba semua baris.
        val startIdx = lines.indexOfFirst { it.contains(HEADER_TOKEN) }
        // +2 skip header baris dan baris kolom header CSV
        val dataLines = if (startIdx >= 0) lines.drop(startIdx + 2) else lines

        var lastTs = 0L
        for (line in dataLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Flags")) continue
            val cols = trimmed.split(",")
            if (cols.size < 14) continue  // minimal 14 kolom

            val flags = cols[0].trim().toLongOrNull() ?: continue
            if (flags != 0L) continue  // skip frame tidak valid

            // col[1] = intended_vsync (ns), col[13] = frame_completed (ns)
            // col[12] = swap_buffers_completed
            val vsync   = cols[1].trim().toLongOrNull() ?: continue
            // Coba col 13 dulu (frame_completed), fallback ke col 12
            val frameEnd = cols.getOrNull(13)?.trim()?.toLongOrNull()
                ?: cols[12].trim().toLongOrNull()
                ?: continue

            if (vsync <= 0L || frameEnd <= 0L) continue
            if (vsync == lastTs) continue     // skip duplicate
            if (frameEnd <= vsync) continue   // frame end harus setelah vsync

            val frameTimeMs = (frameEnd - vsync) / NS_TO_MS
            if (frameTimeMs <= 0f || frameTimeMs > 500f) continue  // >500ms pasti invalid

            result.add(FrameSample(timestamp = vsync, frameTimeMs = frameTimeMs))
            lastTs = vsync
        }
        return result
    }

    fun isValid(raw: String): Boolean {
        if (raw.isBlank()) return false
        val lines = raw.lines()
        // Valid jika ada header atau ada baris CSV dengan >= 14 kolom dan flags=0
        if (lines.any { it.contains(HEADER_TOKEN) }) return true
        return lines.count { line ->
            val cols = line.trim().split(",")
            cols.size >= 14 && cols[0].trim() == "0"
        } >= 2
    }
}

// ─────────────────────────────────────────────────────────────
// GfxinfoTotalFramesParser
// Sumber: dumpsys gfxinfo <pkg>  (tanpa "framestats")
// Parse: "Total frames rendered: X"
// FPS dihitung dari delta frames / delta waktu di FpsMonitor
// ─────────────────────────────────────────────────────────────
object GfxinfoTotalFramesParser {

    private val REGEX = Regex("""Total frames rendered:\s*(\d+)""")

    fun parseTotalFrames(raw: String): Int? {
        return REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun isValid(raw: String): Boolean = REGEX.containsMatchIn(raw)
}

// ─────────────────────────────────────────────────────────────
// DrawProcessParser
// Sumber: dumpsys gfxinfo <pkg>
// Parse baris tiga kolom float: Draw  Process  Execute
// ─────────────────────────────────────────────────────────────
object DrawProcessParser {

    private val LINE_RE = Regex("""^\s*(\d+\.?\d*)\s+(\d+\.?\d*)\s+(\d+\.?\d*)\s*$""")

    fun parse(raw: String): List<FrameSample> {
        val result = mutableListOf<FrameSample>()
        var tsBase = System.nanoTime() - raw.length * 16_000_000L  // estimasi timestamp mundur
        for (line in raw.lines()) {
            // Skip header baris teks
            if (line.contains("Draw") || line.contains("Process") || line.contains("Execute")) continue
            val m = LINE_RE.find(line) ?: continue
            val draw    = m.groupValues[1].toFloatOrNull() ?: continue
            val process = m.groupValues[2].toFloatOrNull() ?: continue
            val execute = m.groupValues[3].toFloatOrNull() ?: continue
            val total = draw + process + execute
            if (total <= 0f || total > 500f) continue
            result.add(FrameSample(timestamp = tsBase, frameTimeMs = total))
            tsBase += (total * 1_000_000).toLong()
        }
        return result
    }

    fun isValid(raw: String): Boolean =
        raw.lines().count { LINE_RE.matches(it.trim()) } >= 5
}

// ─────────────────────────────────────────────────────────────
// SurfaceFlingerParser
// Sumber: dumpsys SurfaceFlinger --latency <window>
// VALIDATION KETAT: harus punya >= 5 baris timestamp valid
// Jika hanya return satu angka (refresh period) → INVALID
// ─────────────────────────────────────────────────────────────
object SurfaceFlingerParser {

    private const val NS_TO_MS = 1_000_000f
    private const val MIN_VALID_FRAMES = 5

    fun parse(raw: String): List<FrameSample> {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        lines[0].trim().toLongOrNull() ?: return emptyList()  // baris pertama = refresh period

        val timestamps = mutableListOf<Long>()
        for (line in lines.drop(1)) {
            val cols = line.trim().split("\\s+".toRegex())
            if (cols.size < 3) continue
            val desired = cols[0].toLongOrNull() ?: continue
            val actual  = cols[1].toLongOrNull() ?: continue
            if (desired <= 0L || actual <= 0L) continue
            timestamps.add(actual)
        }

        if (timestamps.size < MIN_VALID_FRAMES) return emptyList()

        val result = mutableListOf<FrameSample>()
        for (i in 1 until timestamps.size) {
            val diff = timestamps[i] - timestamps[i - 1]
            if (diff <= 0L) continue
            val frameTimeMs = diff / NS_TO_MS
            if (frameTimeMs <= 0f || frameTimeMs > 500f) continue
            result.add(FrameSample(timestamp = timestamps[i], frameTimeMs = frameTimeMs))
        }
        return result
    }

    fun isValid(raw: String): Boolean {
        if (raw.isBlank()) return false
        val lines = raw.lines().filter { it.isNotBlank() }
        // Hanya satu baris = hanya refresh period → INVALID
        if (lines.size <= 1) return false
        // Hitung berapa baris yang punya 3 kolom angka valid
        val validFrameLines = lines.drop(1).count { line ->
            val cols = line.trim().split("\\s+".toRegex())
            cols.size >= 3 && cols[0].toLongOrNull() != null && cols[1].toLongOrNull() != null
        }
        return validFrameLines >= MIN_VALID_FRAMES
    }
}
