package com.javapro.fps

import android.content.Context
import android.util.Log
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TweakShellExecutor(private val context: Context) : ShellExecutor {

    companion object {
        private const val TAG = "FpsShell"
    }

    override suspend fun run(cmd: String): String? = withContext(Dispatchers.IO) {
        try {
            // CRITICAL: pakai executeWithOutput yang sudah handle su/shizuku
            // Tapi harus pastikan pipe/grep bisa jalan via sh -c
            val result = TweakExecutor.executeWithOutput(cmd)
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            Log.w(TAG, "EXCEPTION: cmd='${cmd.take(60)}' err=${e.message}")
            null
        }
    }
}
