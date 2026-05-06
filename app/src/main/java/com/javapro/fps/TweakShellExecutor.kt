package com.javapro.fps

import android.content.Context
import com.javapro.utils.TweakExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TweakShellExecutor(private val context: Context) : ShellExecutor {
    override suspend fun run(cmd: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = TweakExecutor.executeWithOutput(cmd)
            result.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
