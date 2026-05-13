package com.javapro.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PremiumManager {

    private fun dx(b: ByteArray): String = String(ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() })

    private val API_URL     get() = dx(byteArrayOf(0x32, 0x2E, 0x2E, 0x2A, 0x29, 0x60, 0x75, 0x75, 0x30, 0x3B, 0x2C, 0x3B, 0x2A, 0x28, 0x35, 0x77, 0x2A, 0x28, 0x3F, 0x37, 0x33, 0x2F, 0x37, 0x74, 0x2C, 0x3F, 0x28, 0x39, 0x3F, 0x36, 0x74, 0x3B, 0x2A, 0x2A, 0x75, 0x3B, 0x2A, 0x33, 0x75, 0x39, 0x32, 0x3F, 0x39, 0x31))
    private val HMAC_SECRET get() = dx(byteArrayOf(0x6B, 0x3B, 0x6F, 0x6A, 0x6D, 0x3E, 0x3F, 0x39, 0x39, 0x69, 0x3F, 0x3B, 0x6B, 0x3C, 0x39, 0x3E, 0x3C, 0x3E, 0x3B, 0x6A, 0x3E, 0x69, 0x6B, 0x68, 0x3C, 0x3B, 0x3C, 0x6E, 0x62, 0x63, 0x3F, 0x6F, 0x6E, 0x3E, 0x69, 0x6E, 0x6F, 0x6D, 0x6A, 0x63, 0x3E, 0x62, 0x3B, 0x68, 0x62, 0x6A, 0x63, 0x39, 0x6D, 0x63, 0x69, 0x3C, 0x3B, 0x62, 0x6A, 0x6B, 0x6E, 0x6E, 0x3E, 0x62, 0x6E, 0x62, 0x39, 0x6A))
    private const val TS_TOLERANCE_MS = 3 * 60 * 1000L

    private const val PREFS_NAME     = "javapro_premium_prefs"
    private const val KEY_TYPE       = "premium_type"
    private const val KEY_EXPIRY     = "premium_expiry_ms"
    private const val KEY_VERIFIED   = "premium_verified"
    private const val KEY_LAST_CHECK = "premium_last_check"
    private const val KEY_EMAIL      = "premium_email"
    private const val CACHE_TTL_MS   = 5 * 60 * 1000L

    private val REAL_PREMIUM_TYPES = setOf("permanent", "weekly", "monthly", "yearly")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    internal fun getServerHmacSecret(): String = HMAC_SECRET

    private fun prefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            try {
                deletePrefsFile(appContext)
                val freshMasterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_NAME,
                    freshMasterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun deletePrefsFile(context: Context) {
        try {
            val prefsDir  = File(context.applicationInfo.dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "$PREFS_NAME.xml")
            if (prefsFile.exists()) prefsFile.delete()
        } catch (_: Exception) {}
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifySignature(json: JSONObject): Boolean {
        return try {
            val receivedSig = json.optString("sig", "")
            if (receivedSig.isEmpty()) return false
            val keys    = json.keys().asSequence().filter { it != "sig" }.sorted().toList()
            val str     = keys.joinToString(",") { k ->
                val v = json.opt(k).toString()
                buildString { append('"'); append(k); append('"'); append(':'); append('"'); append(v); append('"') }
            }
            val payload  = "{$str}"
            val expected = hmacSha256(HMAC_SECRET, payload)
            receivedSig.length == expected.length && receivedSig.zip(expected).all { (a, b) -> a == b }
        } catch (_: Exception) { false }
    }

    private fun verifyTimestamp(json: JSONObject): Boolean {
        val serverTs = json.optLong("ts", 0L)
        if (serverTs == 0L) return false
        return Math.abs(System.currentTimeMillis() - serverTs) < TS_TOLERANCE_MS
    }

    fun isPremium(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_VERIFIED, false)) return false
        val type   = p.getString(KEY_TYPE, null) ?: return false
        val expiry = p.getLong(KEY_EXPIRY, 0L)
        return when (type) {
            "permanent"                                            -> true
            "weekly", "monthly", "yearly", "coin_reward"          -> System.currentTimeMillis() < expiry
            else                                                   -> false
        }
    }

    fun isRealPremium(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_VERIFIED, false)) return false
        val type   = p.getString(KEY_TYPE, null) ?: return false
        val expiry = p.getLong(KEY_EXPIRY, 0L)
        return when (type) {
            "permanent"           -> true
            in REAL_PREMIUM_TYPES -> System.currentTimeMillis() < expiry
            else                  -> false
        }
    }

    fun getPremiumType(context: Context): String? =
        prefs(context).getString(KEY_TYPE, null)

    fun getExpiryMs(context: Context): Long =
        prefs(context).getLong(KEY_EXPIRY, 0L)

    fun getPremiumEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)

    fun invalidateCache(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, 0L).apply()
    }

    fun clearCacheAndPremium(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC, 0L)
            .remove(KEY_TYPE)
            .remove(KEY_EXPIRY)
            .remove(KEY_EMAIL)
            .putBoolean(KEY_VERIFIED, false)
            .apply()
    }

    suspend fun checkOnline(context: Context, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                val lastCheck = prefs(context).getLong(KEY_LAST_CHECK, 0L)
                if (System.currentTimeMillis() - lastCheck < CACHE_TTL_MS) {
                    return@withContext isPremium(context)
                }
            }

            var user = GoogleAuthManager.silentSignIn(context)
                ?: GoogleAuthManager.getUser(context)

            if (user == null) return@withContext isPremium(context)

            val requestTs = System.currentTimeMillis()

            try {
                val body = JSONObject().apply {
                    put("idToken", user.idToken)
                    put("ts",      requestTs)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder().url(API_URL).post(body).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext isPremium(context)

                val json = JSONObject(response.body?.string() ?: return@withContext isPremium(context))
                if (!verifySignature(json))  return@withContext isPremium(context)
                if (!verifyTimestamp(json))  return@withContext isPremium(context)

                val premium = json.optBoolean("premium", false)
                val type    = json.optString("type", "")
                val expiry  = json.optLong("expiry", 0L)
                val email   = json.optString("email", "")

                prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                if (!premium) {
                    clearCacheAndPremium(context)
                } else {
                    saveCache(context, premium, type.ifEmpty { null }, expiry, email.ifEmpty { null })
                }
                premium
            } catch (_: Exception) {
                isPremium(context)
            }
        }

    fun grantCoinRewardLocally(context: Context, packageId: String, expiryMs: Long) {
        prefs(context).edit().apply {
            putBoolean(KEY_VERIFIED, true)
            putString(KEY_TYPE, "coin_reward")
            putLong(KEY_EXPIRY, expiryMs)
            apply()
        }
    }

    fun clearPremium(context: Context) {
        prefs(context).edit()
            .remove(KEY_TYPE)
            .remove(KEY_EXPIRY)
            .remove(KEY_VERIFIED)
            .remove(KEY_EMAIL)
            .apply()
    }

    private fun saveCache(context: Context, isPremium: Boolean, type: String?, expiryMs: Long, email: String?) {
        prefs(context).edit().apply {
            putBoolean(KEY_VERIFIED, true)
            if (isPremium && type != null) {
                putString(KEY_TYPE,   type)
                putLong(KEY_EXPIRY,   expiryMs)
            } else {
                remove(KEY_TYPE)
                remove(KEY_EXPIRY)
            }
            if (email != null) putString(KEY_EMAIL, email) else remove(KEY_EMAIL)
            apply()
        }
    }

    private const val KEY_LAST_SYNC = "premium_last_check"
}
