package app.playlistmirror.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登入（裝置代碼）與寫回的「解讀與組裝」部分。對應測項 T3-xx。
 * 回應格式依 2026-11-16 實際呼叫 BeatSaver 的結果（BeatSaver 用 verification_url，不是標準的 verification_uri）。
 */
class BeatSaverAuthTest {

    @Test
    fun `T3-01 讀懂裝置代碼的回應`() {
        val code = BeatSaverAuth.parseDeviceCode(
            """{"user_code":"ABCD1234","verification_url":"https://beatsaver.com/quest",
               "verification_url_complete":"https://beatsaver.com/quest?code=ABCD1234",
               "device_code":"5f0c-uuid","interval":5,"expires_in":1799}""",
        )
        assertEquals("ABCD1234", code.userCode)
        assertEquals("https://beatsaver.com/quest", code.verificationUrl)
        assertEquals("5f0c-uuid", code.deviceCode)
        assertEquals(5, code.intervalSeconds)
        assertEquals(1799, code.expiresInSeconds)
    }

    @Test
    fun `T3-01 也接受標準欄位名稱，缺少時用預設值`() {
        val code = BeatSaverAuth.parseDeviceCode("""{"user_code":"X","device_code":"Y","verification_uri":"https://a/b"}""")
        assertEquals("https://a/b", code.verificationUrl)
        assertEquals(5, code.intervalSeconds)
    }

    @Test
    fun `T3-02 換通行證用 BeatSaver 接受的 grant_type（不是標準的長名稱）`() {
        // 標準的 urn:ietf:params:oauth:grant-type:device_code 會被 BeatSaver 以 invalid_grant 拒絕
        assertEquals("device_code", BeatSaverAuth.DEVICE_CODE_GRANT)
    }

    @Test
    fun `T3-02 還沒在網頁上登入：繼續等`() {
        assertEquals(BeatSaverAuth.PollResult.Pending, BeatSaverAuth.parsePoll(400, """{"error":"authorization_pending"}""", 0))
        assertEquals(BeatSaverAuth.PollResult.SlowDown, BeatSaverAuth.parsePoll(400, """{"error":"slow_down"}""", 0))
    }

    @Test
    fun `T3-02 代碼過期或拒絕：顯示原因`() {
        val expired = BeatSaverAuth.parsePoll(400, """{"error":"expired_token"}""", 0)
        assertTrue(expired is BeatSaverAuth.PollResult.Failed && "過期" in expired.reason)
        val denied = BeatSaverAuth.parsePoll(400, """{"error":"access_denied"}""", 0)
        assertTrue(denied is BeatSaverAuth.PollResult.Failed && "拒絕" in denied.reason)
        assertTrue(BeatSaverAuth.parsePoll(500, "", 0) is BeatSaverAuth.PollResult.Failed)
    }

    @Test
    fun `T3-03 登入成功：拿到通行證，並算出過期時間`() {
        val result = BeatSaverAuth.parsePoll(
            200,
            """{"access_token":"AAA","refresh_token":"RRR","expires_in":3600,"token_type":"bearer"}""",
            now = 1_000,
        )
        val tokens = (result as BeatSaverAuth.PollResult.Success).tokens
        assertEquals("AAA", tokens.accessToken)
        assertEquals("RRR", tokens.refreshToken)
        assertEquals(1_000 + 3_600_000L, tokens.expiresAt)
    }

    @Test
    fun `T3-04 續用時沒給新的續用憑證，沿用舊的`() {
        val tokens = BeatSaverAuth.parseTokens("""{"access_token":"NEW","expires_in":60}""", now = 0, previousRefresh = "OLD")
        assertEquals("NEW", tokens.accessToken)
        assertEquals("OLD", tokens.refreshToken)
    }

    @Test
    fun `T3-05 讀懂帳號資訊（ID 可能是數字）`() {
        val identity = BeatSaverAuth.parseIdentity("""{"scopes":["identity"],"id":12345,"name":"tester","avatar":"x"}""")
        assertEquals("12345", identity.id)
        assertEquals("tester", identity.name)
    }

    @Test
    fun `錯誤代碼讀不到時回傳 null`() {
        assertNull(BeatSaverAuth.errorOf("不是 JSON"))
        assertEquals("slow_down", BeatSaverAuth.errorOf("""{"error":"slow_down"}"""))
    }

    @Test
    fun `T3-06 寫回的內容：優先用短 ID，略過 BeatSaver 不認得的歌`() {
        val add = Json.parseToJsonElement(BeatSaverClient.batchBody(listOf("3B2C"), emptyList(), add = true)).jsonObject
        assertEquals(listOf("3b2c"), add["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(add["hashes"]!!.jsonArray.isEmpty())
        assertTrue(add["inPlaylist"]!!.jsonPrimitive.boolean)
        assertTrue(add["ignoreUnknown"]!!.jsonPrimitive.boolean)

        val remove = Json.parseToJsonElement(BeatSaverClient.batchBody(emptyList(), listOf("ABC"), add = false)).jsonObject
        assertEquals(listOf("abc"), remove["hashes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(remove["inPlaylist"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `沒有憑證的版本不能登入`() {
        assertFalse(BeatSaverAuth("", "").configured)
        assertTrue(BeatSaverAuth("id", "secret").configured)
    }
}
