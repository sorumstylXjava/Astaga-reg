package com.javapro.fps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─── Backend confidence & cooldown tracker ──────────────────────
private data class BackendMeta(
    val backend       : FpsBackend,
    var failCount     : Int     = 0,
    var successCount  : Int     = 0,
    var blacklistedUntil: Long  = 0L,   // ms epoch — 0 = not blacklisted
    var lastFrameTs   : Long    = 0L    // untuk stale detection
) {
    val isBlacklisted: Boolean get() = System.currentTimeMillis() < blacklistedUntil

    fun blacklist(durationMs: Long = 15_000L) {
        blacklistedUntil = System.currentTimeMillis() + durationMs
        failCount        = 0
        successCount     = 0
    }

    fun onSuccess() { successCount++; failCount = 0 }
    fun onFail()    { failCount++ }
}

/**
 * FpsEngine — multi-backend FPS engine seperti Scene.
 *
 * Backend priority (sama dengan Scene FpsUtils):
 *   1. SYSFS_MEASURED_FPS  — /sys/.../measured_fps node
 *   2. FPSGO               — /sys/kernel/fpsgo/fstb/fpsgo_status
 *   3. GFXINFO_FRAMESTATS  — dumpsys gfxinfo <pkg> framestats
 *   4. SURFACEFLINGER_LATENCY — dumpsys SurfaceFlinger --latency
 *   5. SF_SERVICE_CALL     — service call SurfaceFlinger 1013
 *   6. GFXINFO_TOTALFRAMES — dumpsys gfxinfo <pkg> (Total frames rendered)
 *   7. GFXINFO_DRAW_PROCESS— dumpsys gfxinfo <pkg> (Draw/Process/Execute)
 *
 * Rules:
 * - Jangan switch backend tiap tick — ada cooldown minimum
 * - Blacklist backend yang gagal terus (15 detik)
 * - Stale detection — jika timestamp tidak berubah
 * - Reject invalid sample (NaN, negatif, > 300fps)
 * - Rolling average untuk smoothing
 */
class FpsEngine(private val executor: ShellExecutor) {

    companion object {
        private const val TAG               = "FpsEngine"
        private const val SWITCH_COOLDOWN_MS = 3_000L   // min 3 detik sebelum ganti backend
        private const val STALE_THRESHOLD_MS = 2_500L   // frame dianggap basi jika tidak berubah 2.5 detik
        private const val MAX_FAIL_BEFORE_BL = 4         // gagal 4x → blacklist
        private const val BLACKLIST_DURATION = 20_000L  // 20 detik blacklist
        private const val HISTORY            = 512
        private const val MAX_VALID_FPS      = 300f
    }

    // Priority order — lebih ringan di atas
    private val PRIORITY = listOf(
        FpsBackend.SYSFS_MEASURED_FPS,
        FpsBackend.FPSGO,
        FpsBackend.GFXINFO_FRAMESTATS,
        FpsBackend.SURFACEFLINGER_LATENCY,
        FpsBackend.GFXINFO_TOTALFRAMES,
        FpsBackend.GFXINFO_DRAW_PROCESS
    )

    private val metas = PRIORITY.associateWith { BackendMeta(it) }.toMutableMap()
    private var activeBackend       = FpsBackend.NONE
    private var lastSwitchMs        = 0L
    private var failReason          = ""
    private var lastShellOutput     = ""
    private var lastTotalFrames     = -1
    private var lastTotalFramesMs   = 0L

    private val frameHistory = RingBuffer<FrameSample>(HISTORY)

    // ── Public API ────────────────────────────────────────────────
    suspend fun poll(pkg: String, refreshHz: Float): EngineResult =
        withContext(Dispatchers.IO) {

            // Pilih backend jika NONE atau perlu switch
            if (activeBackend == FpsBackend.NONE) {
                selectBackend(pkg)
            }

            val frames = fetchFrames(pkg, refreshHz)
            val meta   = metas[activeBackend]!!

            if (frames.isEmpty()) {
                meta.onFail()
                failReason = "FpsFallback: $activeBackend empty frames (fail=${meta.failCount})"
                Log.w(TAG, failReason)

                if (meta.failCount >= MAX_FAIL_BEFORE_BL) {
                    meta.blacklist(BLACKLIST_DURATION)
                    Log.w("FpsFallback", "Blacklisting $activeBackend for ${BLACKLIST_DURATION/1000}s")
                    switchBackend(pkg)
                }
            } else {
                // Stale check — apakah timestamp frame berubah?
                val latestTs = frames.lastOrNull()?.timestamp ?: 0L
                if (latestTs > 0 && latestTs == meta.lastFrameTs) {
                    val staleMs = System.currentTimeMillis() - lastSwitchMs
                    if (staleMs > STALE_THRESHOLD_MS) {
                        meta.onFail()
                        failReason = "FpsFallback: $activeBackend stale (same timestamp $staleMs ms)"
                        Log.w("FpsFallback", failReason)
                        if (meta.failCount >= MAX_FAIL_BEFORE_BL) {
                            meta.blacklist(BLACKLIST_DURATION)
                            switchBackend(pkg)
                        }
                    }
                } else {
                    meta.onSuccess()
                    meta.lastFrameTs = latestTs
                    failReason = ""
                    frames.forEach { frameHistory.add(it) }
                    Log.d("FpsParser", "backend=$activeBackend new=${frames.size} history=${frameHistory.size()}")
                }
            }

            val allFrames = frameHistory.toList()
            val fps       = if (allFrames.size >= 2) FpsCalculator.calculate(allFrames, refreshHz) else FpsStats()

            Log.d(TAG, "FPS_RESULT backend=$activeBackend fps=${fps.currentFps} frames=${fps.totalFrames}")

            EngineResult(
                frames         = allFrames,
                fps            = fps,
                activeBackend  = activeBackend,
                failReason     = failReason,
                parsedCount    = frames.size,
                lastShellOutput = lastShellOutput.take(300)
            )
        }

    // ── Backend selection ─────────────────────────────────────────
    private suspend fun selectBackend(pkg: String) {
        Log.d("FpsBackend", "selectBackend: scanning for '$pkg'")
        for (backend in PRIORITY) {
            val meta = metas[backend]!!
            if (meta.isBlacklisted) {
                Log.d("FpsBackend", "skip $backend — blacklisted until ${meta.blacklistedUntil}")
                continue
            }
            val ok = try { probeBackend(backend, pkg) } catch (e: Exception) {
                Log.e("FpsBackend", "EXCEPTION_STACKTRACE probe $backend", e)
                false
            }
            if (ok) {
                activeBackend = backend
                lastSwitchMs  = System.currentTimeMillis()
                Log.d("FpsBackend", "selected backend=$backend ✓")
                return
            }
        }
        // Last resort — jangan NONE
        activeBackend = FpsBackend.GFXINFO_TOTALFRAMES
        lastSwitchMs  = System.currentTimeMillis()
        failReason    = "FpsFallback: no ideal backend, using GFXINFO_TOTALFRAMES"
        Log.w("FpsBackend", failReason)
    }

    private suspend fun switchBackend(pkg: String) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSwitchMs < SWITCH_COOLDOWN_MS) {
            Log.d("FpsFallback", "switchBackend: cooldown not expired, skip switch")
            return
        }
        Log.w("FpsFallback", "switchBackend: from=$activeBackend")
        val prevBackend = activeBackend
        activeBackend   = FpsBackend.NONE
        frameHistory.clear()
        lastTotalFrames   = -1
        lastTotalFramesMs = 0L
        selectBackend(pkg)
        Log.w("FpsFallback", "switchBackend: $prevBackend → $activeBackend")
    }

    private suspend fun probeBackend(backend: FpsBackend, pkg: String): Boolean {
        return when (backend) {
            FpsBackend.SYSFS_MEASURED_FPS -> {
                measuredFpsPaths().any { path ->
                    val raw = executor.run("cat $path") ?: return@any false
                    (raw.trim().toFloatOrNull() ?: 0f).let { it > 0f && it < MAX_VALID_FPS }
                        .also { Log.d("FpsBackend", "probe SYSFS $path → $it") }
                }
            }
            FpsBackend.FPSGO -> {
                val paths = listOf(
                    "/sys/kernel/fpsgo/fstb/fpsgo_status",
                    "/proc/fpsgo/fstb/fpsgo_status"
                )
                paths.any { path ->
                    val raw = executor.run("cat $path") ?: return@any false
                    raw.contains("fps", ignoreCase = true)
                        .also { Log.d("FpsBackend", "probe FPSGO $path → $it") }
                }
            }
            FpsBackend.GFXINFO_FRAMESTATS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return false
                lastShellOutput = raw.take(300)
                FramestatsParser.isValid(raw)
                    .also { Log.d("FpsBackend", "probe FRAMESTATS len=${raw.length} valid=$it") }
            }
            FpsBackend.SURFACEFLINGER_LATENCY -> {
                // SurfaceFlinger — coba beberapa window name
                sfLatencyWindows(pkg).any { window ->
                    val raw = executor.run("dumpsys SurfaceFlinger --latency \"$window\"") ?: return@any false
                    lastShellOutput = raw.take(300)
                    SurfaceFlingerParser.isValid(raw)
                        .also { Log.d("FpsBackend", "probe SF_LATENCY window='$window' valid=$it") }
                }
            }
            FpsBackend.GFXINFO_TOTALFRAMES -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                GfxinfoTotalFramesParser.isValid(raw)
                    .also { Log.d("FpsBackend", "probe TOTALFRAMES len=${raw.length} valid=$it") }
            }
            FpsBackend.GFXINFO_DRAW_PROCESS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                DrawProcessParser.isValid(raw)
                    .also { Log.d("FpsBackend", "probe DRAW_PROCESS valid=$it") }
            }
            FpsBackend.NONE -> false
        }
    }

    // ── Fetch frames per backend ──────────────────────────────────
    private suspend fun fetchFrames(pkg: String, refreshHz: Float): List<FrameSample> {
        return try {
            when (activeBackend) {
                FpsBackend.SYSFS_MEASURED_FPS -> fetchSysfs()
                FpsBackend.FPSGO              -> fetchFpsgo()
                FpsBackend.GFXINFO_FRAMESTATS -> fetchFramestats(pkg)
                FpsBackend.SURFACEFLINGER_LATENCY -> fetchSfLatency(pkg)
                FpsBackend.GFXINFO_TOTALFRAMES -> fetchTotalFrames(pkg)
                FpsBackend.GFXINFO_DRAW_PROCESS -> fetchDrawProcess(pkg)
                FpsBackend.NONE -> emptyList()
            }
        } catch (e: Exception) {
            Log.e("FpsParser", "EXCEPTION_STACKTRACE fetchFrames $activeBackend", e)
            emptyList()
        }
    }

    private suspend fun fetchSysfs(): List<FrameSample> {
        for (path in measuredFpsPaths()) {
            val raw = executor.run("cat $path")?.trim() ?: continue
            val fps = raw.toFloatOrNull() ?: continue
            if (fps <= 0f || fps > MAX_VALID_FPS) continue
            lastShellOutput = "$path: $fps"
            Log.d("FpsParser", "SYSFS fps=$fps path=$path")
            return syntheticFromFps(fps)
        }
        return emptyList()
    }

    private suspend fun fetchFpsgo(): List<FrameSample> {
        val paths = listOf(
            "/sys/kernel/fpsgo/fstb/fpsgo_status",
            "/proc/fpsgo/fstb/fpsgo_status"
        )
        for (path in paths) {
            val raw = executor.run("cat $path") ?: continue
            lastShellOutput = raw.take(300)
            val fps = Regex("""fps[=:\s]+(\d+\.?\d*)""", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            if (fps <= 0f || fps > MAX_VALID_FPS) continue
            Log.d("FpsParser", "FPSGO fps=$fps")
            return syntheticFromFps(fps)
        }
        return emptyList()
    }

    private suspend fun fetchFramestats(pkg: String): List<FrameSample> {
        val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return emptyList()
        lastShellOutput = raw.take(300)
        val frames = FramestatsParser.parse(raw)
        Log.d("FpsParser", "FRAMESTATS raw=${raw.length} parsed=${frames.size}")
        return frames.filter { isValidSample(it) }
    }

    private suspend fun fetchSfLatency(pkg: String): List<FrameSample> {
        for (window in sfLatencyWindows(pkg)) {
            val raw = executor.run("dumpsys SurfaceFlinger --latency \"$window\"") ?: continue
            lastShellOutput = raw.take(300)
            if (!SurfaceFlingerParser.isValid(raw)) continue
            val frames = SurfaceFlingerParser.parse(raw)
            Log.d("FpsParser", "SF_LATENCY window='$window' parsed=${frames.size}")
            return frames.filter { isValidSample(it) }
        }
        return emptyList()
    }

    private suspend fun fetchTotalFrames(pkg: String): List<FrameSample> {
        val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
        lastShellOutput = raw.take(300)
        val total = GfxinfoTotalFramesParser.parseTotalFrames(raw) ?: return emptyList()
        val nowMs = System.currentTimeMillis()

        if (lastTotalFrames < 0) {
            lastTotalFrames   = total
            lastTotalFramesMs = nowMs
            Log.d("FpsParser", "TOTALFRAMES baseline=$total")
            return emptyList()
        }

        val deltaFrames = total - lastTotalFrames
        val deltaMs     = nowMs - lastTotalFramesMs
        lastTotalFrames   = total
        lastTotalFramesMs = nowMs

        if (deltaFrames <= 0 || deltaMs <= 0) {
            Log.d("FpsParser", "TOTALFRAMES no delta (deltaFrames=$deltaFrames deltaMs=$deltaMs)")
            return emptyList()
        }

        val fps = deltaFrames * 1000f / deltaMs
        Log.d("FpsParser", "TOTALFRAMES delta=$deltaFrames ms=$deltaMs fps=$fps")
        if (fps <= 0f || fps > MAX_VALID_FPS) return emptyList()

        val frameTimeMs = deltaMs.toFloat() / deltaFrames
        var ts = System.nanoTime() - deltaFrames * (frameTimeMs * 1_000_000L).toLong()
        return List(deltaFrames.coerceAtMost(120)) {
            FrameSample(ts, frameTimeMs).also { ts += (frameTimeMs * 1_000_000L).toLong() }
        }
    }

    private suspend fun fetchDrawProcess(pkg: String): List<FrameSample> {
        val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
        lastShellOutput = raw.take(300)
        val frames = DrawProcessParser.parse(raw)
        Log.d("FpsParser", "DRAW_PROCESS parsed=${frames.size}")
        return frames.filter { isValidSample(it) }
    }

    // ── Helpers ───────────────────────────────────────────────────
    private fun syntheticFromFps(fps: Float): List<FrameSample> {
        val ft  = 1000f / fps
        val now = System.nanoTime()
        return listOf(
            FrameSample(now - (ft * 1_000_000L).toLong(), ft),
            FrameSample(now, ft)
        )
    }

    private fun isValidSample(s: FrameSample): Boolean {
        if (s.frameTimeMs.isNaN() || s.frameTimeMs <= 0f) return false
        if (s.frameTimeMs > 1000f) return false  // > 1 detik per frame = tidak valid
        val fps = 1000f / s.frameTimeMs
        return fps > 0f && fps <= MAX_VALID_FPS
    }

    private fun measuredFpsPaths() = listOf(
        "/sys/class/drm/sde-crtc-0/measured_fps",
        "/sys/class/graphics/fb0/measured_fps",
        "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps",
        "/sys/class/drm/card0/card0-DSI-1/measured_fps",
        "/sys/kernel/gpu/gpu_fps"
    )

    private fun sfLatencyWindows(pkg: String) = listOf(
        pkg,
        "$pkg/",
        "$pkg#0",
        "${pkg}.MainActivity",
        ""  // last resort — ambil window aktif tanpa filter
    )

    fun reset() {
        Log.d(TAG, "FpsEngine.reset()")
        activeBackend     = FpsBackend.NONE
        lastSwitchMs      = 0L
        failReason        = ""
        lastShellOutput   = ""
        lastTotalFrames   = -1
        lastTotalFramesMs = 0L
        frameHistory.clear()
        metas.values.forEach {
            it.failCount       = 0
            it.successCount    = 0
            it.blacklistedUntil = 0L
            it.lastFrameTs     = 0L
        }
    }
}

data class EngineResult(
    val frames         : List<FrameSample>,
    val fps            : FpsStats,
    val activeBackend  : FpsBackend,
    val failReason     : String,
    val parsedCount    : Int,
    val lastShellOutput: String
)
