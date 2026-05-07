package com.javapro.fps

import android.util.Log

private const val TAG = "GpuReader"

data class GpuInfo(
    val freqMhz: Int = 0,
    val freqPath: String = "--",
    val loadPercent: Float = -1f,
    val loadPath: String = "--",
    val failReason: String = ""
)

class GpuReader(private val executor: ShellExecutor) {

    // ── Cache path yang sudah ketemu — dicari ulang hanya jika gagal ──
    private var cachedFreqPath: String? = null
    private var cachedLoadPath: String? = null

    suspend fun read(): GpuInfo {
        val (freqMhz, freqPath, freqFail) = readFreq()
        val (load, loadPath, loadFail)    = readLoad()

        val failParts = listOfNotNull(
            freqFail.ifEmpty { null },
            loadFail.ifEmpty { null }
        )
        return GpuInfo(
            freqMhz     = freqMhz,
            freqPath    = freqPath,
            loadPercent = load,
            loadPath    = loadPath,
            failReason  = failParts.joinToString(" | ")
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // GPU FREQUENCY
    // ─────────────────────────────────────────────────────────────────
    private suspend fun readFreq(): Triple<Int, String, String> {
        // Coba cache dulu
        cachedFreqPath?.let { p ->
            val v = tryReadFreqPath(p)
            if (v > 0) return Triple(v, p, "")
            cachedFreqPath = null
            Log.d(TAG, "Freq cache miss: $p")
        }

        // 1. Snapdragon / KGSL — prioritas tertinggi
        val kgslPaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/devfreq/kgsl-3d0/cur_freq"
        )
        for (p in kgslPaths) {
            val v = tryReadFreqPath(p)
            if (v > 0) { cachedFreqPath = p; return Triple(v, p, "") }
        }

        // 2. MediaTek / Mali — devfreq gpufreq node
        val mtkPaths = listOf(
            "/sys/class/devfreq/gpufreq/cur_freq",
            "/sys/class/devfreq/mtk-mali/cur_freq",
            "/sys/class/devfreq/mali/cur_freq"
        )
        for (p in mtkPaths) {
            val v = tryReadFreqPath(p)
            if (v > 0) { cachedFreqPath = p; return Triple(v, p, "") }
        }

        // 3. Exynos / generic Mali platform
        val exynosPaths = listOf(
            "/sys/devices/platform/mali.0/devfreq/mali.0/cur_freq",
            "/sys/devices/platform/13000000.mali/devfreq/13000000.mali/cur_freq"
        )
        for (p in exynosPaths) {
            val v = tryReadFreqPath(p)
            if (v > 0) { cachedFreqPath = p; return Triple(v, p, "") }
        }

        // 4. MTK gpufreq proc node
        val procPaths = listOf(
            "/proc/gpufreq/gpufreq_var_dump",
            "/proc/gpufreq/gpufreq_opp_freq"
        )
        for (p in procPaths) {
            val raw = executor.run("cat $p") ?: continue
            val mhz = parseProcGpuFreq(raw)
            if (mhz > 0) { cachedFreqPath = p; return Triple(mhz, p, "") }
        }

        // 5. Scan /sys/class/devfreq/* — cari folder gpu/mali/kgsl
        val scanResult = scanDevfreqForFreq()
        if (scanResult != null) {
            cachedFreqPath = scanResult.second
            return Triple(scanResult.first, scanResult.second, "")
        }

        return Triple(0, "--", "gpu_freq: no valid path found")
    }

    private suspend fun tryReadFreqPath(path: String): Int {
        val raw = executor.run("cat $path")?.trim() ?: return 0
        return hzToMhz(raw.toLongOrNull() ?: return 0)
    }

    private fun hzToMhz(hz: Long): Int = when {
        hz <= 0L            -> 0
        hz > 1_000_000_000L -> (hz / 1_000_000_000L).toInt()  // gigahertz in Hz
        hz > 10_000_000L    -> (hz / 1_000_000L).toInt()       // megahertz in Hz
        hz > 10_000L        -> (hz / 1_000L).toInt()            // kilohertz
        else                -> hz.toInt()                        // sudah MHz
    }

    private fun parseProcGpuFreq(raw: String): Int {
        // "freq = 500000" atau "500000 kHz" atau "500 MHz"
        Regex("""(?i)freq[=:\s]+(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { return hzToMhz(it) }
        // fallback: ambil angka pertama yang masuk akal
        raw.lines().forEach { line ->
            val num = Regex("""\d{5,}""").find(line)?.value?.toLongOrNull() ?: return@forEach
            val mhz = hzToMhz(num)
            if (mhz in 10..5000) return mhz
        }
        return 0
    }

    private suspend fun scanDevfreqForFreq(): Pair<Int, String>? {
        // Baca semua folder devfreq sekaligus lewat satu perintah
        val raw = executor.run(
            "for d in /sys/class/devfreq/*/; do " +
            "n=\$(basename \$d); " +
            "v=\$(cat \${d}cur_freq 2>/dev/null); " +
            "echo \"\$n:\$v\"; done"
        ) ?: return null

        val keywords = listOf("gpu", "mali", "kgsl", "adreno", "sgx")
        for (line in raw.lines()) {
            val parts = line.split(":")
            if (parts.size < 2) continue
            val name = parts[0].lowercase()
            val raw2 = parts[1].trim()
            if (keywords.none { name.contains(it) }) continue
            val hz = raw2.toLongOrNull() ?: continue
            val mhz = hzToMhz(hz)
            if (mhz > 0) {
                val fullPath = "/sys/class/devfreq/${parts[0]}/cur_freq"
                return Pair(mhz, fullPath)
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────
    // GPU LOAD / UTILIZATION
    // ─────────────────────────────────────────────────────────────────
    private suspend fun readLoad(): Triple<Float, String, String> {
        cachedLoadPath?.let { p ->
            val v = tryReadLoadPath(p)
            if (v >= 0f) return Triple(v, p, "")
            cachedLoadPath = null
            Log.d(TAG, "Load cache miss: $p")
        }

        // Daftar path + parser khusus per path
        data class LoadCandidate(val path: String, val parser: String = "percent")

        val candidates = listOf(
            // Snapdragon / KGSL
            LoadCandidate("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"),
            LoadCandidate("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load"),
            LoadCandidate("/sys/class/kgsl/kgsl-3d0/gpubusy",  parser = "busy_total"),
            LoadCandidate("/sys/class/kgsl/kgsl-3d0/gpuload"),
            // Generic
            LoadCandidate("/sys/kernel/gpu/gpu_busy"),
            // MediaTek GED
            LoadCandidate("/sys/kernel/debug/ged/hal/gpu_utilization", parser = "ged"),
            LoadCandidate("/sys/kernel/ged/hal/gpu_utilization",       parser = "ged"),
            LoadCandidate("/sys/module/ged/parameters/gpu_loading"),
            LoadCandidate("/sys/class/devfreq/gpufreq/mali_ondemand/utilisation"),
            // Exynos / generic Mali
            LoadCandidate("/sys/devices/platform/1c500000.mali/utilization"),
            LoadCandidate("/sys/devices/platform/mali.0/utilization")
        )

        for (c in candidates) {
            val raw = executor.run("cat ${c.path}")?.trim() ?: continue
            if (raw.isEmpty()) continue
            val v = when (c.parser) {
                "busy_total" -> parseBusyTotal(raw)
                "ged"        -> parseGed(raw)
                else         -> parsePercent(raw)
            }
            if (v >= 0f) {
                cachedLoadPath = c.path
                return Triple(v, c.path, "")
            }
        }

        return Triple(-1f, "--", "gpu_load: no valid path found")
    }

    private suspend fun tryReadLoadPath(path: String): Float {
        val raw = executor.run("cat $path")?.trim() ?: return -1f
        return when {
            path.contains("gpubusy")   -> parseBusyTotal(raw)
            path.contains("ged")       -> parseGed(raw)
            else                       -> parsePercent(raw)
        }
    }

    // "35" atau "35%" → 35f
    private fun parsePercent(raw: String): Float {
        val cleaned = raw.replace("%", "").trim()
        val v = cleaned.toFloatOrNull() ?: return -1f
        return if (v in 0f..100f) v else -1f
    }

    // "busy total" format kgsl: "12345678 98765432" → busy/total * 100
    private fun parseBusyTotal(raw: String): Float {
        val parts = raw.trim().split("\\s+".toRegex())
        if (parts.size < 2) return -1f
        val busy  = parts[0].toLongOrNull() ?: return -1f
        val total = parts[1].toLongOrNull() ?: return -1f
        if (total <= 0L) return -1f
        return (busy.toFloat() / total * 100f).coerceIn(0f, 100f)
    }

    // GED/MTK: baris berisi "Loading: 35" atau angka campuran, ambil yang pertama masuk akal
    private fun parseGed(raw: String): Float {
        // Coba "Loading: 35" atau "loading=35"
        Regex("""(?i)load(?:ing)?[=:\s]+(\d+)""").find(raw)
            ?.groupValues?.get(1)?.toFloatOrNull()
            ?.let { if (it in 0f..100f) return it }
        // Fallback: angka pertama 0-100
        Regex("""\b(\d{1,3})\b""").findAll(raw).forEach { m ->
            val v = m.value.toFloatOrNull() ?: return@forEach
            if (v in 0f..100f) return v
        }
        return -1f
    }

    fun reset() {
        cachedFreqPath = null
        cachedLoadPath = null
    }
}
