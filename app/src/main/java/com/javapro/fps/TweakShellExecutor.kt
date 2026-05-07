package com.javapro.fps

import android.content.Context
import android.util.Log
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TweakShellExecutor(private val context: Context) : ShellExecutor {

    companion object {
        private const val TAG = "FpsShell"
        // Set true untuk log tiap perintah shell — matikan di production
        private const val VERBOSE = false
    }

    override suspend fun run(cmd: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = TweakExecutor.executeWithOutput(cmd)
            if (VERBOSE) {
                Log.v(TAG, "CMD: $cmd → ${result.length} chars")
            }
            result.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "EXCEPTION_STACKTRACE: cmd='$cmd' error=${e.message}")
            null
        }
    }
}
