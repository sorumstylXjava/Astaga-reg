package com.javapro.fps

import android.util.Log

/**
 * FpsSurfaceResolver — resolve active window/surface untuk SurfaceFlinger backend.
 * Mirip Scene FpsADBUtilsBase: cari surface name yang valid dari SurfaceFlinger --list.
 */
class FpsSurfaceResolver(private val executor: ShellExecutor) {

    companion object {
        private const val TAG = "FpsResolver"
    }

    /**
     * Cari surface name terbaik untuk package.
     * Return: list of candidate surface names, diurutkan dari paling spesifik.
     */
    suspend fun resolveSurfaces(pkg: String): List<String> {
        Log.d(TAG, "resolveSurfaces: pkg=$pkg")

        // 1. Coba dari cache dulu
        FpsSessionCache.surfaceCache[pkg]?.let { cached ->
            Log.d(TAG, "resolveSurfaces: cache hit surface='$cached'")
            return listOf(cached) + genericCandidates(pkg)
        }

        // 2. Ambil list surface dari SurfaceFlinger
        val sfList = resolveSurfaceFromSF(pkg)
        if (sfList.isNotEmpty()) {
            // Cache yang pertama berhasil
            FpsSessionCache.surfaceCache[pkg] = sfList.first()
            FpsSessionCache.currentSurface    = sfList.first()
            Log.d(TAG, "resolveSurfaces: resolved surface='${sfList.first()}'")
        }

        // 3. Gabung dengan generic candidates
        return (sfList + genericCandidates(pkg)).distinct()
    }

    private suspend fun resolveSurfaceFromSF(pkg: String): List<String> {
        // dumpsys SurfaceFlinger --list → cari baris yang contain package name
        val raw = executor.run("dumpsys SurfaceFlinger --list") ?: run {
            Log.w(TAG, "resolveSurfaces: SurfaceFlinger --list returned null")
            return emptyList()
        }

        val matches = raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                (line.contains(pkg, ignoreCase = true) ||
                 line.contains("SurfaceView", ignoreCase = true))
            }
            .sortedByDescending { line ->
                // Prioritaskan yang paling spesifik ke package
                when {
                    line.contains(pkg) && line.contains("SurfaceView") -> 3
                    line.contains(pkg) -> 2
                    line.contains("SurfaceView") -> 1
                    else -> 0
                }
            }

        Log.d(TAG, "resolveSurfaces: SF list matched ${matches.size} surfaces for $pkg")
        matches.take(3).forEach { Log.d(TAG, "  surface candidate: '$it'") }

        return matches.take(5)
    }

    private fun genericCandidates(pkg: String) = listOf(
        pkg,
        "$pkg/",
        "$pkg#0",
        "$pkg/.MainActivity",
        "$pkg/com.unity3d.player.UnityPlayerActivity",
        ""  // last resort — SurfaceFlinger aktif tanpa filter
    )

    /**
     * Resolve focused app dari dumpsys window.
     */
    suspend fun resolveFocusedApp(): String? {
        val raw = executor.run("dumpsys window | grep mCurrentFocus") ?: return null
        // Format: mCurrentFocus=Window{... u0 com.example.app/com.example.app.MainActivity}
        val match = Regex("""([a-zA-Z][a-zA-Z0-9_]*\.[a-zA-Z][a-zA-Z0-9_.]+)/""").find(raw)
        val pkg = match?.groupValues?.get(1)
        Log.d(TAG, "resolveFocusedApp: focusedApp=$pkg raw='${raw.take(100)}'")
        return pkg
    }
}
