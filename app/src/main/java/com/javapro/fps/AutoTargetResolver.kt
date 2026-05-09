package com.javapro.fps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AutoTargetResolver — terus resolve foreground app + surface setiap polling cycle.
 *
 * Mirip FloatMonitorFPS.e (dynamic target field) di Scene:
 * - Detect foreground app via dumpsys window
 * - Resolve surface name via SurfaceFlinger --list
 * - Cache hasil per package
 * - Return dynamic target setiap poll
 *
 * Consumer (FpsMonitorManager) inject target ini ke FpsEngine setiap tick.
 */
class AutoTargetResolver(private val executor: ShellExecutor) {

    companion object {
        private const val TAG = "FpsResolver"

        // Package yang dilewati (system UI, launcher, dsb)
        private val SKIP_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.miui.systemui",
            "com.samsung.android.app.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.oneplus.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.javapro"  // skip app kita sendiri
        )
    }

    // Dynamic target — diupdate setiap resolve
    @Volatile var currentTarget: ResolvedTarget = ResolvedTarget.empty()

    // Cache: pkg → surface candidates
    private val surfaceCache = mutableMapOf<String, List<String>>()

    /**
     * Resolve target terbaru. Dipanggil setiap polling cycle.
     * Return cached target jika resolve gagal.
     */
    suspend fun resolve(): ResolvedTarget = withContext(Dispatchers.IO) {
        val pkg = resolveFocusedPackage()

        if (pkg == null) {
            Log.d(TAG, "resolve: no focused pkg, using cached=${currentTarget.pkg}")
            return@withContext currentTarget
        }

        if (pkg in SKIP_PACKAGES) {
            Log.d(TAG, "resolve: skip system pkg=$pkg, using cached=${currentTarget.pkg}")
            return@withContext currentTarget.takeIf { it.isValid() }
                ?: ResolvedTarget.empty()
        }

        // Jika package sama dengan sebelumnya, refresh surface saja tiap 10 resolve
        val surfaces = if (pkg == currentTarget.pkg && surfaceCache.containsKey(pkg)) {
            surfaceCache[pkg]!!
        } else {
            resolveSurfaces(pkg).also { surfaceCache[pkg] = it }
        }

        val target = ResolvedTarget(pkg = pkg, surfaces = surfaces)
        currentTarget = target

        Log.d(TAG, "resolve: focusedApp=$pkg surface=${surfaces.firstOrNull()} candidates=${surfaces.size}")
        target
    }

    // ── Focused package ──────────────────────────────────────────
    private suspend fun resolveFocusedPackage(): String? {
        // Method 1: dumpsys window mCurrentFocus
        val fromWindow = resolveFocusedFromWindow()
        if (fromWindow != null) return fromWindow

        // Method 2: dumpsys activity top-resumed-activity
        val fromActivity = resolveFocusedFromActivity()
        if (fromActivity != null) return fromActivity

        Log.w(TAG, "resolveFocusedPackage: all methods failed")
        return null
    }

    private suspend fun resolveFocusedFromWindow(): String? {
        val raw = executor.run("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'")
            ?: return null
        // Format: mCurrentFocus=Window{... u0 com.example.app/Activity}
        val pkg = Regex("""u\d\s+([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)/""")
            .find(raw)?.groupValues?.get(1)
        Log.d(TAG, "focusedWindow: pkg=$pkg")
        return pkg?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveFocusedFromActivity(): String? {
        val raw = executor.run("dumpsys activity top-resumed-activity") ?: return null
        // Format: "packageName=com.example.app"
        val pkg = Regex("""packageName=([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)""")
            .find(raw)?.groupValues?.get(1)
            ?: Regex("""([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)/[A-Z]""")
                .find(raw)?.groupValues?.get(1)
        Log.d(TAG, "focusedActivity: pkg=$pkg")
        return pkg?.takeIf { it.isNotBlank() }
    }

    // ── Surface resolution ────────────────────────────────────────
    private suspend fun resolveSurfaces(pkg: String): List<String> {
        val sfSurfaces = resolveSurfacesFromSF(pkg)
        val generics   = genericCandidates(pkg)
        return (sfSurfaces + generics).distinct()
    }

    private suspend fun resolveSurfacesFromSF(pkg: String): List<String> {
        val raw = executor.run("dumpsys SurfaceFlinger --list") ?: run {
            Log.w(TAG, "SurfaceFlinger --list: null output")
            return emptyList()
        }

        val matches = raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(pkg, ignoreCase = true) }
            .sortedByDescending { line ->
                when {
                    line.contains(pkg) && line.contains("SurfaceView") -> 4
                    line.contains(pkg) && line.contains("#") -> 3
                    line.contains(pkg) -> 2
                    else -> 0
                }
            }

        Log.d(TAG, "SurfaceFlinger --list: found ${matches.size} matches for $pkg")
        matches.take(3).forEach { Log.d(TAG, "  surface: '$it'") }
        return matches.take(6)
    }

    private fun genericCandidates(pkg: String) = listOf(
        pkg,
        "$pkg/",
        "$pkg#0",
        "$pkg/.MainActivity",
        "$pkg/com.unity3d.player.UnityPlayerActivity",
        "$pkg/com.cocos2dx.lib.Cocos2dxActivity",
        "$pkg/com.epicgames.ue4.GameActivity",
        ""   // last resort
    )

    fun clearCache() {
        surfaceCache.clear()
        currentTarget = ResolvedTarget.empty()
        Log.d(TAG, "clearCache")
    }
}

/**
 * ResolvedTarget — hasil resolve per cycle.
 * pkg: package foreground app saat ini
 * surfaces: daftar candidate surface name untuk SurfaceFlinger
 */
data class ResolvedTarget(
    val pkg     : String,
    val surfaces: List<String>
) {
    fun isValid(): Boolean = pkg.isNotBlank()
    fun primarySurface(): String = surfaces.firstOrNull() ?: pkg

    companion object {
        fun empty() = ResolvedTarget(pkg = "", surfaces = emptyList())
    }
}
