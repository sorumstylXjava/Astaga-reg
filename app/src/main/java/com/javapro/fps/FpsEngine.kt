package com.javapro.fps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class BackendMeta(
    val backend         : FpsBackend,
    var failCount       : Int  = 0,
    var successCount    : Int  = 0,
    var blacklistedUntil: Long = 0L,
    var lastFrameTs     : Long = 0L
) {
    val isBlacklisted: Boolean get() = System.currentTimeMillis() < blacklistedUntil
    fun blacklist(ms: Long = 20_000L) { blacklistedUntil = System.currentTimeMillis() + ms; failCount = 0; successCount = 0 }
    fun onSuccess() { successCount++; failCount = 0 }
    fun onFail()    { failCount++ }
}

class FpsEngine(private val executor: ShellExecutor) {

    companion object {
        private const val TAG          = "FpsEngine"
        private const val TAG_BACKEND  = "FpsBackend"
        private const val TAG_FALLBACK = "FpsFallback"
        private const val TAG_PARSER   = "FpsParser"
        private const val SWITCH_COOLDOWN = 3_000L
        private const val STALE_MS        = 2_500L
        private const val MAX_FAIL        = 4
        private const val BLACKLIST_MS    = 20_000L
        private const val HISTORY         = 512
        private const val MAX_FPS         = 300f
    }

    private val PRIORITY = listOf(
        FpsBackend.SYSFS_MEASURED_FPS,
        FpsBackend.FPSGO,
        FpsBackend.GFXINFO_FRAMESTATS,
        FpsBackend.SURFACEFLINGER_LATENCY,
        FpsBackend.GFXINFO_TOTALFRAMES,
        FpsBackend.GFXINFO_DRAW_PROCESS
    )

    private val metas = PRIORITY.associateWith { BackendMeta(it) }.toMutableMap()

    private var activeBackend     = FpsBackend.NONE
    private var lastSwitchMs      = 0L
    private var failReason        = ""
    private var lastShellOutput   = ""
    private var lastTotalFrames   = -1
    private var lastTotalFramesMs = 0L
    private var cachedSurfaces    = emptyList<String>()
    private var tickCount         = 0

    // CRITICAL: track current pkg — reset engine jika pkg berubah
    private var lastPkg = ""

    private val frameHistory = RingBuffer<FrameSample>(HISTORY)

    suspend fun poll(target: ResolvedTarget, refreshHz: Float): EngineResult =
        withContext(Dispatchers.IO) {
            val pkg = target.pkg
            tickCount++

            // ── CRITICAL: Reset engine jika pkg berubah ───────────
            if (pkg != lastPkg && lastPkg.isNotEmpty()) {
                Log.w(TAG, "pkg changed: $lastPkg → $pkg — resetting engine")
                resetInternal()
            }
            lastPkg = pkg

            // ── Update cached surfaces dari resolver ──────────────
            if (target.surfaces.isNotEmpty()) {
                cachedSurfaces = target.surfaces
            }

            // ── Select backend jika belum ada ─────────────────────
            if (activeBackend == FpsBackend.NONE) {
                selectBackend(pkg)
            }

            // ── Fetch frames ──────────────────────────────────────
            val frames = try {
                fetchFrames(pkg)
            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION_STACKTRACE: fetchFrames $activeBackend", e)
                emptyList()
            }

            val meta = metas[activeBackend]!!

            if (frames.isEmpty()) {
                meta.onFail()
                failReason = "empty: $activeBackend fail=${meta.failCount}"
                Log.w(TAG_FALLBACK, "from=$activeBackend reason=empty fail=${meta.failCount}")
                if (meta.failCount >= MAX_FAIL) {
                    meta.blacklist(BLACKLIST_MS)
                    Log.w(TAG_FALLBACK, "blacklisted $activeBackend for ${BLACKLIST_MS/1000}s")
                    switchBackend(pkg)
                }
            } else {
                val latestTs = frames.lastOrNull()?.timestamp ?: 0L
                if (latestTs > 0 && latestTs == meta.lastFrameTs) {
                    val staleMs = System.currentTimeMillis() - lastSwitchMs
                    if (staleMs > STALE_MS) {
                        meta.onFail()
                        failReason = "stale: $activeBackend age=${staleMs}ms"
                        Log.w(TAG_FALLBACK, failReason)
                        if (meta.failCount >= MAX_FAIL) {
                            meta.blacklist(BLACKLIST_MS)
                            switchBackend(pkg)
                        }
                    }
                } else {
                    meta.onSuccess()
                    meta.lastFrameTs = latestTs
                    failReason       = ""
                    frames.forEach { frameHistory.add(it) }
                    Log.d(TAG_PARSER, "backend=$activeBackend frameCount=${frames.size} history=${frameHistory.size()}")
                }
            }

            val allFrames = frameHistory.toList()
            val fps       = if (allFrames.size >= 2) FpsCalculator.calculate(allFrames, refreshHz) else FpsStats()

            Log.d(TAG_BACKEND, "backend=$activeBackend fps=${fps.currentFps} valid=${fps.currentFps > 0f}")

            FpsSessionCache.lastBackend = activeBackend
            if (fps.currentFps > 0f) FpsSessionCache.lastGoodFps = fps.currentFps

            EngineResult(
                frames          = allFrames,
                fps             = fps,
                activeBackend   = activeBackend,
                failReason      = failReason,
                parsedCount     = frames.size,
                lastShellOutput = lastShellOutput.take(300)
            )
        }

    // ── Backend selection ─────────────────────────────────────────
    private suspend fun selectBackend(pkg: String) {
        Log.d(TAG_BACKEND, "selectBackend: pkg=$pkg")
        for (backend in PRIORITY) {
            val meta = metas[backend]!!
            if (meta.isBlacklisted) { Log.d(TAG_BACKEND, "skip $backend — blacklisted"); continue }
            val ok = try { probeBackend(backend, pkg) } catch (e: Exception) {
                Log.e(TAG_BACKEND, "probe exception $backend", e); false
            }
            if (ok) {
                activeBackend = backend
                lastSwitchMs  = System.currentTimeMillis()
                Log.d(TAG_BACKEND, "selected backend=$backend ✓")
                return
            }
        }
        // Last resort — GFXINFO_TOTALFRAMES hampir selalu ada
        activeBackend = FpsBackend.GFXINFO_TOTALFRAMES
        lastSwitchMs  = System.currentTimeMillis()
        failReason    = "no ideal backend, using GFXINFO_TOTALFRAMES"
        Log.w(TAG_BACKEND, failReason)
    }

    private suspend fun switchBackend(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastSwitchMs < SWITCH_COOLDOWN) {
            Log.d(TAG_FALLBACK, "switchBackend: cooldown ${now - lastSwitchMs}ms < ${SWITCH_COOLDOWN}ms, skip")
            return
        }
        val prev = activeBackend
        activeBackend     = FpsBackend.NONE
        frameHistory.clear()
        lastTotalFrames   = -1
        lastTotalFramesMs = 0L
        selectBackend(pkg)
        Log.w(TAG_FALLBACK, "switchBackend: $prev → $activeBackend")
    }

    private suspend fun probeBackend(backend: FpsBackend, pkg: String): Boolean {
        Log.d(TAG_BACKEND, "probeBackend: $backend pkg=$pkg")
        return when (backend) {
            FpsBackend.SYSFS_MEASURED_FPS -> {
                measuredFpsPaths().any { path ->
                    val raw = executor.run("cat $path") ?: return@any false
                    val fps = raw.trim().toFloatOrNull() ?: return@any false
                    (fps > 0f && fps < MAX_FPS).also { Log.d(TAG_BACKEND, "probe SYSFS $path fps=$fps valid=$it") }
                }
            }
            FpsBackend.FPSGO -> {
                fpsGoPaths().any { path ->
                    val raw = executor.run("cat $path") ?: return@any false
                    raw.contains("fps", ignoreCase = true)
                        .also { Log.d(TAG_BACKEND, "probe FPSGO $path valid=$it") }
                }
            }
            FpsBackend.GFXINFO_FRAMESTATS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return false
                lastShellOutput = raw.take(300)
                FramestatsParser.isValid(raw)
                    .also { Log.d(TAG_BACKEND, "probe FRAMESTATS len=${raw.length} valid=$it") }
            }
            FpsBackend.SURFACEFLINGER_LATENCY -> {
                cachedSurfaces.any { surface ->
                    val cmd = if (surface.isEmpty()) "dumpsys SurfaceFlinger --latency"
                              else "dumpsys SurfaceFlinger --latency \"$surface\""
                    val raw = executor.run(cmd) ?: return@any false
                    lastShellOutput = raw.take(300)
                    SurfaceFlingerParser.isValid(raw)
                        .also { Log.d(TAG_BACKEND, "probe SF_LATENCY surface='$surface' valid=$it len=${raw.length}") }
                }
            }
            FpsBackend.GFXINFO_TOTALFRAMES -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                GfxinfoTotalFramesParser.isValid(raw)
                    .also { Log.d(TAG_BACKEND, "probe TOTALFRAMES len=${raw.length} valid=$it") }
            }
            FpsBackend.GFXINFO_DRAW_PROCESS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return false
                lastShellOutput = raw.take(300)
                DrawProcessParser.isValid(raw)
                    .also { Log.d(TAG_BACKEND, "probe DRAW_PROCESS valid=$it") }
            }
            FpsBackend.NONE -> false
        }
    }

    // ── Fetch frames ───────────────────────────────────────────────
    private suspend fun fetchFrames(pkg: String): List<FrameSample> {
        return when (activeBackend) {
            FpsBackend.SYSFS_MEASURED_FPS -> {
                for (path in measuredFpsPaths()) {
                    val fps = executor.run("cat $path")?.trim()?.toFloatOrNull() ?: continue
                    if (fps <= 0f || fps >= MAX_FPS) continue
                    lastShellOutput = "$path=$fps"
                    Log.d(TAG_PARSER, "SYSFS fps=$fps path=$path")
                    return syntheticFromFps(fps)
                }
                emptyList()
            }
            FpsBackend.FPSGO -> {
                for (path in fpsGoPaths()) {
                    val raw = executor.run("cat $path") ?: continue
                    lastShellOutput = raw.take(200)
                    val fps = Regex("""fps[=:\s]+(\d+\.?\d*)""", RegexOption.IGNORE_CASE)
                        .find(raw)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
                    if (fps <= 0f || fps >= MAX_FPS) continue
                    Log.d(TAG_PARSER, "FPSGO fps=$fps")
                    return syntheticFromFps(fps)
                }
                emptyList()
            }
            FpsBackend.GFXINFO_FRAMESTATS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg framestats") ?: return emptyList()
                lastShellOutput = raw.take(300)
                val frames = FramestatsParser.parse(raw).filter { isValidSample(it) }
                Log.d(TAG_PARSER, "FRAMESTATS rawLen=${raw.length} parsed=${frames.size}")
                frames
            }
            FpsBackend.SURFACEFLINGER_LATENCY -> {
                for (surface in cachedSurfaces) {
                    val cmd = if (surface.isEmpty()) "dumpsys SurfaceFlinger --latency"
                              else "dumpsys SurfaceFlinger --latency \"$surface\""
                    val raw = executor.run(cmd) ?: continue
                    lastShellOutput = raw.take(300)
                    if (!SurfaceFlingerParser.isValid(raw)) continue
                    val frames = SurfaceFlingerParser.parse(raw).filter { isValidSample(it) }
                    Log.d(TAG_PARSER, "SF_LATENCY surface='$surface' parsed=${frames.size}")
                    if (frames.isNotEmpty()) return frames
                }
                emptyList()
            }
            FpsBackend.GFXINFO_TOTALFRAMES -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
                lastShellOutput = raw.take(300)
                val total = GfxinfoTotalFramesParser.parseTotalFrames(raw) ?: return emptyList()
                val nowMs = System.currentTimeMillis()
                if (lastTotalFrames < 0) {
                    lastTotalFrames   = total
                    lastTotalFramesMs = nowMs
                    Log.d(TAG_PARSER, "TOTALFRAMES baseline=$total")
                    return emptyList()
                }
                val delta  = total - lastTotalFrames
                val deltaMs = nowMs - lastTotalFramesMs
                lastTotalFrames   = total
                lastTotalFramesMs = nowMs
                if (delta <= 0 || deltaMs <= 0) {
                    Log.d(TAG_PARSER, "TOTALFRAMES no delta delta=$delta ms=$deltaMs")
                    return emptyList()
                }
                val fps = delta * 1000f / deltaMs
                Log.d(TAG_PARSER, "TOTALFRAMES delta=$delta ms=$deltaMs fps=$fps")
                if (fps <= 0f || fps >= MAX_FPS) return emptyList()
                val ft = deltaMs.toFloat() / delta
                var ts = System.nanoTime() - delta * (ft * 1_000_000L).toLong()
                List(delta.coerceAtMost(120)) {
                    FrameSample(ts, ft).also { ts += (ft * 1_000_000L).toLong() }
                }
            }
            FpsBackend.GFXINFO_DRAW_PROCESS -> {
                val raw = executor.run("dumpsys gfxinfo $pkg") ?: return emptyList()
                lastShellOutput = raw.take(300)
                val frames = DrawProcessParser.parse(raw).filter { isValidSample(it) }
                Log.d(TAG_PARSER, "DRAW_PROCESS parsed=${frames.size}")
                frames
            }
            FpsBackend.NONE -> emptyList()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────
    private fun syntheticFromFps(fps: Float): List<FrameSample> {
        val ft  = 1000f / fps
        val now = System.nanoTime()
        return listOf(FrameSample(now - (ft * 1_000_000L).toLong(), ft), FrameSample(now, ft))
    }

    private fun isValidSample(s: FrameSample): Boolean {
        if (s.frameTimeMs.isNaN() || s.frameTimeMs <= 0f || s.frameTimeMs > 1000f) return false
        return 1000f / s.frameTimeMs in 1f..MAX_FPS
    }

    private fun measuredFpsPaths() = listOf(
        "/sys/class/drm/sde-crtc-0/measured_fps",
        "/sys/class/graphics/fb0/measured_fps",
        "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-sde-crtc-0/measured_fps",
        "/sys/class/drm/card0/card0-DSI-1/measured_fps",
        "/sys/kernel/gpu/gpu_fps"
    )

    private fun fpsGoPaths() = listOf(
        "/sys/kernel/fpsgo/fstb/fpsgo_status",
        "/proc/fpsgo/fstb/fpsgo_status"
    )

    // Reset state internal tanpa touch metas blacklist
    private fun resetInternal() {
        activeBackend     = FpsBackend.NONE
        lastSwitchMs      = 0L
        failReason        = ""
        lastShellOutput   = ""
        lastTotalFrames   = -1
        lastTotalFramesMs = 0L
        cachedSurfaces    = emptyList()
        tickCount         = 0
        frameHistory.clear()
        // Reset fail/success tapi pertahankan blacklist agar tidak probe ulang backend yang sudah jelas gagal
        metas.values.forEach { it.failCount = 0; it.successCount = 0; it.lastFrameTs = 0L }
    }

    fun reset() {
        Log.d(TAG, "FpsEngine.reset()")
        lastPkg = ""
        resetInternal()
        // Full reset — hapus juga blacklist
        metas.values.forEach { it.blacklistedUntil = 0L }
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
