package app.playlistmirror.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 用「裝置代碼」登入 BeatSaver（OAuth 的一種流程，專為不方便打字的裝置設計）。
 *
 * 流程：
 * 1. App 向 BeatSaver 申請一組裝置代碼，拿到 8 字代碼和網址（beatsaver.com/quest）
 * 2. 你用手機或電腦打開網址、輸入代碼、登入並允許
 * 3. App 每隔幾秒問一次「登入了沒」，登入後就拿到通行證（access token）
 * 4. 通行證會過期，到期前用續用憑證（refresh token）換一張新的，不用重新登入
 *
 * 要用到的 Client ID 與 Secret 在建置時從 secrets.properties 放進 App（不在 Git 裡）。
 */
class BeatSaverAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val apiBase: String = "https://api.beatsaver.com",
) {
    /** 這個版本有沒有帶 BeatSaver 憑證。沒有時無法登入。 */
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    data class DeviceCode(
        val userCode: String,
        val verificationUrl: String,
        val deviceCode: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    /** 通行證。expiresAt 是過期的時間點（毫秒）。 */
    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long)

    data class Identity(val id: String, val name: String)

    sealed interface PollResult {
        data class Success(val tokens: Tokens) : PollResult
        /** 你還沒在網頁上完成登入，等一下再問 */
        data object Pending : PollResult
        /** 問太快了，間隔要拉長 */
        data object SlowDown : PollResult
        /** 代碼過期、你按了拒絕，或其他錯誤 */
        data class Failed(val reason: String) : PollResult
    }

    private val scope = "identity playlists playlists.manage"

    /** 步驟 1：申請裝置代碼。 */
    fun requestDeviceCode(): DeviceCode {
        val (status, body) = postForm("$apiBase/oauth2/devicecode", mapOf("scope" to scope))
        if (status != 200) throw IOException("申請登入代碼失敗：${errorOf(body) ?: "HTTP $status"}")
        return parseDeviceCode(body)
    }

    /** 步驟 3：問一次「登入了沒」。 */
    fun poll(deviceCode: String, now: Long = System.currentTimeMillis()): PollResult {
        val (status, body) = postForm(
            "$apiBase/oauth2/token",
            mapOf("grant_type" to DEVICE_CODE_GRANT, "device_code" to deviceCode),
        )
        return parsePoll(status, body, now)
    }

    /** 步驟 4：用續用憑證換一張新的通行證。 */
    fun refresh(refreshToken: String, now: Long = System.currentTimeMillis()): Tokens {
        val (status, body) = postForm(
            "$apiBase/oauth2/token",
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
        )
        if (status == 400 || status == 401) throw LoginRejected("登入已失效，請重新登入（${errorOf(body) ?: "HTTP $status"}）")
        if (status != 200) throw IOException("續用登入失敗（HTTP $status）")
        return parseTokens(body, now, previousRefresh = refreshToken)
    }

    /** BeatSaver 明確拒絕續用（例如續用憑證已過期或被撤銷），必須重新登入。 */
    class LoginRejected(message: String) : IOException(message)

    /** 查詢目前登入的是誰。 */
    fun identity(accessToken: String): Identity {
        val connection = (URL("$apiBase/oauth2/identity").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            if (connection.responseCode != 200) throw IOException("查詢帳號失敗（HTTP ${connection.responseCode}）")
            return parseIdentity(connection.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            connection.disconnect()
        }
    }

    /** 送出表單，並附上 Client ID 與 Secret（BeatSaver 要求兩者都要給）。 */
    private fun postForm(url: String, fields: Map<String, String>): Pair<Int, String> {
        val all = fields + mapOf("client_id" to clientId, "client_secret" to clientSecret)
        val body = all.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to (stream?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val USER_AGENT = "PlaylistMirror/0.1"

        /**
         * 用裝置代碼換通行證時的 grant_type。
         * 國際標準寫法是 "urn:ietf:params:oauth:grant-type:device_code"，但 BeatSaver 不接受，
         * 只接受簡短的 "device_code"（2026-11-16 實測）。
         */
        const val DEVICE_CODE_GRANT = "device_code"

        // ───── 解讀 BeatSaver 的回應（獨立出來，方便測試）─────

        fun parseDeviceCode(body: String): DeviceCode {
            val o = Json.parseToJsonElement(body).jsonObject
            return DeviceCode(
                userCode = o.str("user_code") ?: throw IOException("回應缺少登入代碼"),
                // BeatSaver 用 verification_url；標準寫法是 verification_uri，兩種都接受
                verificationUrl = o.str("verification_url") ?: o.str("verification_uri") ?: "https://beatsaver.com/quest",
                deviceCode = o.str("device_code") ?: throw IOException("回應缺少裝置代碼"),
                intervalSeconds = (o["interval"] as? JsonPrimitive)?.intOrNull ?: 5,
                expiresInSeconds = (o["expires_in"] as? JsonPrimitive)?.intOrNull ?: 1800,
            )
        }

        fun parsePoll(status: Int, body: String, now: Long): PollResult {
            if (status == 200) return PollResult.Success(parseTokens(body, now, previousRefresh = null))
            return when (val error = errorOf(body)) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                "expired_token" -> PollResult.Failed("代碼已過期，請重新登入")
                "access_denied" -> PollResult.Failed("你在網頁上拒絕了授權")
                null -> PollResult.Failed("登入失敗（HTTP $status）")
                else -> PollResult.Failed("登入失敗：$error")
            }
        }

        fun parseTokens(body: String, now: Long, previousRefresh: String?): Tokens {
            val o = Json.parseToJsonElement(body).jsonObject
            val expiresIn = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
            return Tokens(
                accessToken = o.str("access_token") ?: throw IOException("回應缺少通行證"),
                // 續用時 BeatSaver 可能不給新的續用憑證，這時沿用舊的
                refreshToken = o.str("refresh_token") ?: previousRefresh,
                expiresAt = now + expiresIn * 1000,
            )
        }

        fun parseIdentity(body: String): Identity {
            val o = Json.parseToJsonElement(body).jsonObject
            return Identity(
                id = o.str("id") ?: throw IOException("回應缺少使用者 ID（是否有 identity 權限？）"),
                name = o.str("name") ?: "",
            )
        }

        fun errorOf(body: String): String? =
            runCatching { Json.parseToJsonElement(body).jsonObject.str("error") }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
