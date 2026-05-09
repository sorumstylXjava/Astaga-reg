package com.javapro.fps

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AutoTargetResolver — continuously detect foreground app + surface.
 *
 * FIX utama:
 * 1. Semua perintah dengan pipe (grep, awk) di-wrap "sh -c '...'" agar
 *    bisa dijalankan via Runtime.exec()
 * 2. SF --list hanya di-resolve saat pkg BERUBAH, bukan setiap tick
 *    (terlalu lambat → freeze)
 * 3. Focused-app resolve pakai method cepat dulu (window dump minimal)
 */
class AutoTargetResolver(private val executor: ShellExecutor) {

    companion object {
        private const val TAG = "FpsResolver"

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
            "com.javapro"
        )
    }

    @Volatile var currentTarget: ResolvedTarget = ResolvedTarget.empty()

    // Cache surface per pkg — hanya refresh saat pkg berubah
    private val surfaceCache = mutableMapOf<String, List<String>>()
    private var lastResolvedPkg = ""

    /**
     * Resolve target terbaru.
     * Dipanggil setiap polling cycle oleh FpsMonitorManager.
     *
     * PENTING: resolve focused-app CEPAT (tanpa pipe grep bila mungkin).
     * Surface resolve hanya saat pkg berubah.
     */
    suspend fun resolve(): ResolvedTarget = withContext(Dispatchers.IO) {
        val pkg = resolveFocusedPackage()

        if (pkg == null) {
            Log.d(TAG, "resolve: no focused pkg, using cached=${currentTarget.pkg}")
            return@withContext currentTarget
        }

        if (pkg in SKIP_PACKAGES) {
            Log.d(TAG, "resolve: skip system pkg=$pkg, keep cached=${currentTarget.pkg}")
            return@withContext currentTarget.takeIf { it.isValid() } ?: ResolvedTarget.empty()
        }

        // Hanya re-resolve surface saat pkg berubah
        val surfaces = if (pkg == lastResolvedPkg && surfaceCache.containsKey(pkg)) {
            surfaceCache[pkg]!!
        } else {
            Log.d(TAG, "resolve: pkg changed $lastResolvedPkg → $pkg, resolving surfaces")
            resolveSurfaces(pkg).also {
                surfaceCache[pkg] = it
                lastResolvedPkg   = pkg
            }
        }

        val target = ResolvedTarget(pkg = pkg, surfaces = surfaces)
        currentTarget = target

        Log.d(TAG, "focusedApp=$pkg surface=${surfaces.firstOrNull()} total=${surfaces.size}")
        target
    }

    // ── Focused package — CEPAT, tanpa pipe ──────────────────────
    private suspend fun resolveFocusedPackage(): String? {
        // Method 1: dumpsys window — tanpa grep, parse di Kotlin
        val fromWindow = resolveFocusedFromWindowFast()
        if (fromWindow != null) return fromWindow

        // Method 2: dumpsys activity top-resumed-activity — biasanya lebih lambat
        val fromActivity = resolveFocusedFromActivity()
        if (fromActivity != null) return fromActivity

        Log.w(TAG, "resolveFocusedPackage: all methods failed")
        return null
    }

    /**
     * Parse dumpsys window tanpa grep — lebih cepat, tidak perlu pipe.
     * Langsung ambil output lalu cari pola di Kotlin.
     */
    private suspend fun resolveFocusedFromWindowFast(): String? {
        // Ambil hanya baris yang relevan — batasi output agar cepat
        val raw = executor.run("dumpsys window displays") ?: return null

        // Cari mCurrentFocus atau mFocusedApp
        val line = raw.lines().firstOrNull { line ->
            line.contains("mCurrentFocus", ignoreCase = true) ||
            line.contains("mFocusedApp",  ignoreCase = true)
        } ?: return null

        // Format: u0 com.package.name/Activity atau com.package.name/Activity
        val pkg = Regex("""(?:u\d\s+)?([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+){1,})/""")
            .find(line)?.groupValues?.get(1)

        Log.d(TAG, "focusedWindow: pkg=$pkg line='${line.take(80)}'")
        return pkg?.takeIf { it.isNotBlank() && it.contains('.') }
    }

    private suspend fun resolveFocusedFromActivity(): String? {
        val raw = executor.run("dumpsys activity top-resumed-activity") ?: return null
        val pkg = Regex("""packageName=([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)""")
            .find(raw)?.groupValues?.get(1)
            ?: Regex("""([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)/[A-Z]""")
                .find(raw)?.groupValues?.get(1)
        Log.d(TAG, "focusedActivity: pkg=$pkg")
        return pkg?.takeIf { it.isNotBlank() }
    }

    // ── Surface — hanya dipanggil saat pkg berubah ────────────────
    private suspend fun resolveSurfaces(pkg: String): List<String> {
        val sfSurfaces = resolveSurfacesFromSF(pkg)
        val generics   = genericCandidates(pkg)
        val all        = (sfSurfaces + generics).distinct()
        Log.d(TAG, "surfaces for $pkg: total=${all.size} sf=${sfSurfaces.size}")
        return all
    }

    private suspend fun resolveSurfacesFromSF(pkg: String): List<String> {
        // Tidak pakai pipe — parse di Kotlin
        val raw = executor.run("dumpsys SurfaceFlinger --list") ?: run {
            Log.w(TAG, "SurfaceFlinger --list: null")
            return emptyList()
        }

        val matches = raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains(pkg, ignoreCase = true) }
            .sortedByDescending { line ->
                when {
                    line.contains(pkg) && line.contains("SurfaceView") -> 4
                    line.contains(pkg) && line.contains("#")            -> 3
                    line.contains(pkg)                                  -> 2
                    else                                                -> 0
                }
            }

        Log.d(TAG, "SF --list: ${matches.size} matches for $pkg")
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
        ""  // last resort — window aktif apapun
    )

    fun clearCache() {
        surfaceCache.clear()
        currentTarget   = ResolvedTarget.empty()
        lastResolvedPkg = ""
        Log.d(TAG, "clearCache")
    }
}

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
