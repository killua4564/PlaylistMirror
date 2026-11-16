package app.playlistmirror.sync

import android.content.Context

/**
 * 登入狀態的保管箱：記住通行證與帳號，並在需要時自動續用。
 *
 * 通行證存在 App 的私人空間（其他 App 讀不到），登出就清掉。
 */
class BeatSaverSession(context: Context, val auth: BeatSaverAuth) {

    private val prefs = context.getSharedPreferences("beatsaver_session", Context.MODE_PRIVATE)

    data class Account(val userId: String, val name: String)

    val account: Account?
        get() {
            val id = prefs.getString("user_id", null) ?: return null
            if (prefs.getString("access_token", null) == null) return null
            return Account(id, prefs.getString("user_name", null).orEmpty())
        }

    fun save(tokens: BeatSaverAuth.Tokens, identity: BeatSaverAuth.Identity) {
        prefs.edit()
            .putString("access_token", tokens.accessToken)
            .putString("refresh_token", tokens.refreshToken)
            .putLong("expires_at", tokens.expiresAt)
            .putString("user_id", identity.id)
            .putString("user_name", identity.name)
            .apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    /**
     * 拿一張可用的通行證；快過期（剩不到 5 分鐘）就先續用。
     * 沒登入時回傳 null。BeatSaver 明確拒絕續用時自動登出；
     * 只是網路問題時不登出，這次先回傳 null，下次再試。
     * 會連網，請在背景執行。
     */
    @Synchronized
    fun validAccessToken(now: Long = System.currentTimeMillis()): String? {
        val access = prefs.getString("access_token", null) ?: return null
        val expiresAt = prefs.getLong("expires_at", 0)
        if (expiresAt - now > 5 * 60_000) return access

        val refresh = prefs.getString("refresh_token", null)
        if (refresh == null) {
            logout()
            return null
        }
        return try {
            val tokens = auth.refresh(refresh, now)
            prefs.edit()
                .putString("access_token", tokens.accessToken)
                .putString("refresh_token", tokens.refreshToken)
                .putLong("expires_at", tokens.expiresAt)
                .apply()
            tokens.accessToken
        } catch (_: BeatSaverAuth.LoginRejected) {
            logout()
            null
        } catch (_: Exception) {
            null
        }
    }
}
