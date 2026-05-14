package com.javapro.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CoinManager {

    private fun dx(b: ByteArray): String = String(ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() })

    private val COIN_API_BASE  get() = dx(byteArrayOf(0x32, 0x2E, 0x2E, 0x2A, 0x29, 0x60, 0x75, 0x75, 0x30, 0x3B, 0x2C, 0x3B, 0x2A, 0x28, 0x35, 0x77, 0x2A, 0x28, 0x3F, 0x37, 0x33, 0x2F, 0x37, 0x74, 0x2C, 0x3F, 0x28, 0x39, 0x3F, 0x36, 0x74, 0x3B, 0x2A, 0x2A, 0x75, 0x3B, 0x2A, 0x33, 0x75))
    private val HMAC_SECRET    get() = dx(byteArrayOf(0x6B, 0x3B, 0x6F, 0x6A, 0x6D, 0x3E, 0x3F, 0x39, 0x39, 0x69, 0x3F, 0x3B, 0x6B, 0x3C, 0x39, 0x3E, 0x3C, 0x3E, 0x3B, 0x6A, 0x3E, 0x69, 0x6B, 0x68, 0x3C, 0x3B, 0x3C, 0x6E, 0x62, 0x63, 0x3F, 0x6F, 0x6E, 0x3E, 0x69, 0x6E, 0x6F, 0x6D, 0x6A, 0x63, 0x3E, 0x62, 0x3B, 0x68, 0x62, 0x6A, 0x63, 0x39, 0x6D, 0x63, 0x69, 0x3C, 0x3B, 0x62, 0x6A, 0x6B, 0x6E, 0x6E, 0x3E, 0x62, 0x6E, 0x62, 0x39, 0x6A))

    private const val PREFS_NAME        = "jp_coin_v1"
    private const val KEY_BALANCE       = "c1b"
    private const val KEY_BALANCE_SIG   = "c1s"
    private const val KEY_LAST_SYNC_MS  = "c2l"
    private const val CACHE_TTL_MS      = 5 * 60 * 1000L

    const val COIN_PER_AD_MIN   = 10
    const val COIN_PER_AD_MAX   = 15

    const val PACKAGE_WEEKLY_ID    = "weekly"
    const val PACKAGE_MONTHLY_ID   = "monthly"
    const val PACKAGE_YEARLY_ID    = "yearly"
    const val PACKAGE_WEEKLY_COST  = 300
    const val PACKAGE_MONTHLY_COST = 700
    const val PACKAGE_YEARLY_COST  = 1800

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    sealed class EarnResult {
        data class Success(val coinsEarned: Int, val newBalance: Int) : EarnResult()
        object AdFraudDetected  : EarnResult()
        object TamperDetected   : EarnResult()
        object NetworkError     : EarnResult()
        object ServerError      : EarnResult()
    }

    sealed class RedeemResult {
        data class Success(val packageId: String, val expiryMs: Long, val remainingBalance: Int) : RedeemResult()
        data class InsufficientCoins(val required: Int, val current: Int) : RedeemResult()
        object AlreadyPremium   : RedeemResult()
        object TamperDetected   : RedeemResult()
        object NetworkError     : RedeemResult()
        object ServerError      : RedeemResult()
    }

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

    private fun hmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun buildSig(vararg pairs: Pair<String, String>): String {
        val payload = "{" + pairs.sortedBy { it.first }
            .joinToString(",") { (k, v) -> "\"$k\":\"$v\"" } + "}"
        return hmac(payload)
    }

    private fun verifyHmac(data: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        val expected = hmac(data)
        return stored.length == expected.length && stored.zip(expected).all { (a, b) -> a == b }
    }

    private fun verifyServerSignature(json: JSONObject): Boolean {
        return try {
            val receivedSig = json.optString("sig", "")
            if (receivedSig.isEmpty()) return false
            val keys    = json.keys().asSequence().filter { it != "sig" }.sorted().toList()
            val payload = keys.joinToString(",") { k -> "\"$k\":\"${json.get(k)}\"" }.let { "{$it}" }
            val mac     = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(PremiumManager.getServerHmacSecret().toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            receivedSig.length == expected.length && receivedSig.zip(expected).all { (a, b) -> a == b }
        } catch (_: Exception) { false }
    }

    private fun verifyTimestamp(json: JSONObject): Boolean {
        val serverTs = json.optLong("ts", 0L)
        if (serverTs == 0L) return false
        return kotlin.math.abs(System.currentTimeMillis() - serverTs) < 10 * 60 * 1000L
    }

    fun getDeviceFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val raw = buildString {
            append(androidId)
            append(Build.BOARD)
            append(Build.BRAND)
            append(Build.DEVICE)
            append(Build.HARDWARE)
            append(Build.MANUFACTURER)
            append(Build.MODEL)
            append(Build.PRODUCT)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun getCachedBalance(context: Context): Int {
        val p   = prefs(context)
        val v   = p.getInt(KEY_BALANCE, 0)
        val sig = p.getString(KEY_BALANCE_SIG, null)
        if (v == 0) return 0
        return if (verifyHmac(v.toString(), sig)) v else 0
    }

    private fun saveCachedBalance(context: Context, balance: Int) {
        prefs(context).edit().apply {
            putInt(KEY_BALANCE, balance)
            putString(KEY_BALANCE_SIG, hmac(balance.toString()))
            putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
            apply()
        }
    }

    suspend fun fetchBalance(context: Context, forceRefresh: Boolean = false): Int = withContext(Dispatchers.IO) {
        val p        = prefs(context)
        val lastSync = p.getLong(KEY_LAST_SYNC_MS, 0L)
        val now      = System.currentTimeMillis()
        if (!forceRefresh && (now - lastSync) < CACHE_TTL_MS) return@withContext getCachedBalance(context)

        val user = GoogleAuthManager.silentSignIn(context)
            ?: GoogleAuthManager.getUser(context)
            ?: return@withContext getCachedBalance(context)

        try {
            val ts  = now
            val sig = buildSig("idToken" to user.idToken, "ts" to ts.toString())
            val body = JSONObject().apply {
                put("idToken", user.idToken)
                put("ts",      ts)
                put("sig",     sig)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${COIN_API_BASE}coin-balance")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext getCachedBalance(context)

            val json = JSONObject(response.body?.string() ?: return@withContext getCachedBalance(context))
            if (!verifyServerSignature(json)) return@withContext getCachedBalance(context)
            if (!verifyTimestamp(json))       return@withContext getCachedBalance(context)

            val balance = json.optString("balance", "0").toIntOrNull() ?: 0
            saveCachedBalance(context, balance)
            balance
        } catch (_: Exception) {
            getCachedBalance(context)
        }
    }

    suspend fun earnCoins(context: Context): EarnResult = withContext(Dispatchers.IO) {
        val user = GoogleAuthManager.silentSignIn(context)
            ?: GoogleAuthManager.getUser(context)
            ?: return@withContext EarnResult.NetworkError

        if (!AdWatchValidator.isAdDurationValid(context)) return@withContext EarnResult.AdFraudDetected

        try {
            val ts         = System.currentTimeMillis()
            val nonce      = UUID.randomUUID().toString()
            val deviceId   = getDeviceFingerprint(context)
            val adStartMs  = AdWatchValidator.getAdStartMs(context)
            val sig        = buildSig(
                "adStartMs" to adStartMs.toString(),
                "deviceId"  to deviceId,
                "idToken"   to user.idToken,
                "nonce"     to nonce,
                "ts"        to ts.toString(),
            )

            val body = JSONObject().apply {
                put("idToken",   user.idToken)
                put("ts",        ts)
                put("nonce",     nonce)
                put("sig",       sig)
                put("deviceId",  deviceId)
                put("adStartMs", adStartMs)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${COIN_API_BASE}coin-earn")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 400) return@withContext EarnResult.ServerError

            val json = JSONObject(response.body?.string() ?: return@withContext EarnResult.ServerError)
            if (!verifyServerSignature(json)) return@withContext EarnResult.TamperDetected
            if (!verifyTimestamp(json))       return@withContext EarnResult.TamperDetected

            return@withContext when {
                json.optString("success") == "true" -> {
                    val earned     = json.optString("coinsEarned", "0").toIntOrNull() ?: 0
                    val newBalance = json.optString("balance", "0").toIntOrNull() ?: 0
                    saveCachedBalance(context, newBalance)
                    AdWatchValidator.clearAdStart(context)
                    EarnResult.Success(earned, newBalance)
                }
                json.optString("reason") == "fraud_detected" -> EarnResult.AdFraudDetected
                else                                          -> EarnResult.ServerError
            }
        } catch (_: java.net.UnknownHostException)           { EarnResult.NetworkError  }
          catch (_: java.net.SocketTimeoutException)          { EarnResult.NetworkError  }
          catch (_: javax.net.ssl.SSLPeerUnverifiedException) { EarnResult.TamperDetected }
          catch (_: Exception)                                { EarnResult.ServerError   }
    }

    suspend fun redeemPackage(context: Context, packageId: String): RedeemResult = withContext(Dispatchers.IO) {
        val cost = when (packageId) {
            PACKAGE_WEEKLY_ID  -> PACKAGE_WEEKLY_COST
            PACKAGE_MONTHLY_ID -> PACKAGE_MONTHLY_COST
            PACKAGE_YEARLY_ID  -> PACKAGE_YEARLY_COST
            else               -> return@withContext RedeemResult.ServerError
        }

        val currentBalance = fetchBalance(context, forceRefresh = true)
        if (currentBalance < cost) return@withContext RedeemResult.InsufficientCoins(cost, currentBalance)

        val user = GoogleAuthManager.silentSignIn(context)
            ?: GoogleAuthManager.getUser(context)
            ?: return@withContext RedeemResult.NetworkError

        try {
            val ts       = System.currentTimeMillis()
            val nonce    = UUID.randomUUID().toString()
            val deviceId = getDeviceFingerprint(context)
            val sig      = buildSig(
                "deviceId"  to deviceId,
                "idToken"   to user.idToken,
                "nonce"     to nonce,
                "packageId" to packageId,
                "ts"        to ts.toString(),
            )

            val body = JSONObject().apply {
                put("idToken",   user.idToken)
                put("packageId", packageId)
                put("ts",        ts)
                put("nonce",     nonce)
                put("sig",       sig)
                put("deviceId",  deviceId)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${COIN_API_BASE}coin-redeem")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 400) return@withContext RedeemResult.ServerError

            val json = JSONObject(response.body?.string() ?: return@withContext RedeemResult.ServerError)
            if (!verifyServerSignature(json)) return@withContext RedeemResult.TamperDetected
            if (!verifyTimestamp(json))       return@withContext RedeemResult.TamperDetected

            return@withContext when {
                json.optString("success") == "true" -> {
                    val expiryMs         = json.optString("expiryMs", "0").toLongOrNull() ?: 0L
                    val remainingBalance = json.optString("balance", "0").toIntOrNull() ?: 0
                    saveCachedBalance(context, remainingBalance)
                    PremiumManager.grantCoinRewardLocally(context, packageId, expiryMs)
                    RedeemResult.Success(packageId, expiryMs, remainingBalance)
                }
                json.optString("reason") == "insufficient_coins" -> {
                    val required = json.optString("required", cost.toString()).toIntOrNull() ?: cost
                    val current  = json.optString("balance", currentBalance.toString()).toIntOrNull() ?: currentBalance
                    saveCachedBalance(context, current)
                    RedeemResult.InsufficientCoins(required, current)
                }
                json.optString("reason") == "already_premium" -> RedeemResult.AlreadyPremium
                else                                           -> RedeemResult.ServerError
            }
        } catch (_: java.net.UnknownHostException)           { RedeemResult.NetworkError    }
          catch (_: java.net.SocketTimeoutException)          { RedeemResult.NetworkError    }
          catch (_: javax.net.ssl.SSLPeerUnverifiedException) { RedeemResult.TamperDetected  }
          catch (_: Exception)                                { RedeemResult.ServerError     }
    }

    fun invalidateCache(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SYNC_MS, 0L).apply()
    }

    sealed class DebugCoinResult {
        data class Success(val newBalance: Int) : DebugCoinResult()
        object NetworkError : DebugCoinResult()
        object ServerError  : DebugCoinResult()
        object NoUser       : DebugCoinResult()
    }

    suspend fun debugUpdateServerBalance(
        context : Context,
        action  : String,
        amount  : Int = 0,
    ): DebugCoinResult = withContext(Dispatchers.IO) {
        val user = GoogleAuthManager.silentSignIn(context)
            ?: GoogleAuthManager.getUser(context)
            ?: return@withContext DebugCoinResult.NoUser

        try {
            val body = JSONObject().apply {
                put("email",  user.email)
                put("action", action)
                if (action != "reset") put("amount", amount)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${COIN_API_BASE}coin-debug")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext DebugCoinResult.ServerError

            val json       = JSONObject(response.body?.string() ?: return@withContext DebugCoinResult.ServerError)
            val newBalance = json.optInt("balance", 0)
            invalidateCache(context)
            saveCachedBalance(context, newBalance)
            DebugCoinResult.Success(newBalance)
        } catch (_: java.net.UnknownHostException)  { DebugCoinResult.NetworkError }
          catch (_: java.net.SocketTimeoutException) { DebugCoinResult.NetworkError }
          catch (_: Exception)                       { DebugCoinResult.ServerError  }
    }

    fun debugRedeemPackage(context: Context, packageId: String): RedeemResult {
        val cost = when (packageId) {
            PACKAGE_WEEKLY_ID  -> PACKAGE_WEEKLY_COST
            PACKAGE_MONTHLY_ID -> PACKAGE_MONTHLY_COST
            PACKAGE_YEARLY_ID  -> PACKAGE_YEARLY_COST
            else               -> return RedeemResult.ServerError
        }
        val durationMs = when (packageId) {
            PACKAGE_WEEKLY_ID  -> 7L  * 24 * 60 * 60 * 1000L
            PACKAGE_MONTHLY_ID -> 30L * 24 * 60 * 60 * 1000L
            PACKAGE_YEARLY_ID  -> 365L* 24 * 60 * 60 * 1000L
            else               -> return RedeemResult.ServerError
        }
        val current = getCachedBalance(context)
        if (current < cost) return RedeemResult.InsufficientCoins(cost, current)

        val newBalance = current - cost
        saveCachedBalance(context, newBalance)
        val expiryMs = System.currentTimeMillis() + durationMs
        PremiumManager.grantCoinRewardLocally(context, packageId, expiryMs)
        return RedeemResult.Success(packageId, expiryMs, newBalance)
    }
}
