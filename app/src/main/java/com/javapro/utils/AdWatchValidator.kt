package com.javapro.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

object AdWatchValidator {

    private const val PREFS_NAME          = "jp_adwatch_v1"
    private const val KEY_AD_START        = "a1s"
    const val MIN_AD_DURATION_MS          = 15_000L
    private const val MAX_AD_SESSION_MS   = 5 * 60 * 1000L

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            try {
                val prefsDir  = File(context.applicationInfo.dataDir, "shared_prefs")
                val prefsFile = File(prefsDir, "$PREFS_NAME.xml")
                if (prefsFile.exists()) prefsFile.delete()
                val freshKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    freshKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    fun markAdStart(context: Context) {
        prefs(context).edit().putLong(KEY_AD_START, System.currentTimeMillis()).apply()
    }

    fun getAdStartMs(context: Context): Long =
        prefs(context).getLong(KEY_AD_START, 0L)

    fun isAdDurationValid(context: Context): Boolean {
        val startMs = prefs(context).getLong(KEY_AD_START, 0L)
        if (startMs == 0L) return true
        val elapsed = System.currentTimeMillis() - startMs
        if (elapsed > MAX_AD_SESSION_MS) return false
        return elapsed >= MIN_AD_DURATION_MS
    }

    fun clearAdStart(context: Context) {
        prefs(context).edit().putLong(KEY_AD_START, 0L).apply()
    }
}
