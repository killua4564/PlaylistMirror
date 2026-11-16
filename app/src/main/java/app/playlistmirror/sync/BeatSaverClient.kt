package app.playlistmirror.sync

import app.playlistmirror.data.PlaylistLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 和 BeatSaver 溝通的連線工具（目前只有讀取；寫回要等階段 3 拿到授權）。
 *
 * 用到的 API 都已在規格書「技術方向與資料」章節確認過。
 *
 * @param accessToken 登入後拿到的通行證；沒有登入時是 null，只能讀公開歌單
 */
class BeatSaverClient(
    private val accessToken: String? = null,
    private val apiBase: String = "https://api.beatsaver.com",
) {

    /** 讀取歌單的結果。私人歌單在沒登入時，BeatSaver 也會回「找不到」，兩者要分開。 */
    sealed interface PlaylistResult {
        data class Found(val playlist: CloudPlaylist) : PlaylistResult
        /** 有登入、BeatSaver 仍說找不到：確定已刪除 */
        data object Deleted : PlaylistResult
        /** 沒登入、BeatSaver 說找不到：可能是私人歌單，無法判斷 */
        data object NotVisible : PlaylistResult
    }

    /** 下載一個歌單的完整內容（和 Quest 上的歌單檔同格式）。 */
    fun fetchPlaylist(id: String): PlaylistResult {
        val (status, body) = get("$apiBase/playlists/id/$id/download")
        return when (status) {
            200 -> PlaylistResult.Found(parsePlaylist(id, Json.parseToJsonElement(body!!).jsonObject))
            404 -> if (accessToken != null) PlaylistResult.Deleted else PlaylistResult.NotVisible
            else -> throw IOException("讀取歌單 $id 失敗（HTTP $status）")
        }
    }

    /** 查歌單擁有者的使用者 ID；查不到回傳 null。 */
    fun fetchOwnerId(playlistId: String): String? {
        val (status, body) = get("$apiBase/playlists/id/$playlistId/0")
        if (status != 200) return null
        val playlist = Json.parseToJsonElement(body!!).jsonObject["playlist"] as? JsonObject
        return ((playlist?.get("owner") as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
    }

    /** 某位使用者的所有歌單 ID（BeatSaver 一次給一頁，讀到空頁為止）。 */
    fun fetchUserPlaylistIds(userId: String): List<String> {
        val ids = mutableListOf<String>()
        var page = 0
        while (true) {
            val (status, body) = get("$apiBase/playlists/user/$userId/$page")
            if (status != 200) throw IOException("讀取使用者歌單失敗（HTTP $status）")
            val docs = (Json.parseToJsonElement(body!!).jsonObject["docs"] as? JsonArray).orEmpty()
            if (docs.isEmpty()) break
            docs.mapNotNullTo(ids) { ((it as? JsonObject)?.get("playlistId") as? JsonPrimitive)?.contentOrNull }
            page++
            if (page > 50) break // 防止意外的無限迴圈
        }
        return ids
    }

    /** BeatSaver 上一首歌的資訊（最新版本）。 */
    data class MapInfo(
        val key: String,
        val hash: String,
        val downloadUrl: String,
        val songName: String,
        val mapper: String?,
    )

    /**
     * 用指紋查歌曲資訊。BeatSaver 一次最多查 50 個，這裡自動分批。
     * 查不到的指紋不會出現在結果裡。
     */
    fun mapsByHashes(hashes: Collection<String>): Map<String, MapInfo> {
        val result = mutableMapOf<String, MapInfo>()
        for (chunk in hashes.map { it.lowercase() }.distinct().chunked(50)) {
            val (status, body) = get("$apiBase/maps/hash/${chunk.joinToString(",")}")
            if (status == 404) continue
            if (status != 200) throw IOException("查詢歌曲失敗（HTTP $status）")
            val root = Json.parseToJsonElement(body!!).jsonObject
            // 只查一個指紋時，BeatSaver 直接回那首歌；查多個時，回「指紋 → 歌曲」的對照表
            val pairs: List<Pair<String, JsonElement?>> =
                if (chunk.size == 1) listOf(chunk[0] to root) else root.entries.map { it.key.lowercase() to it.value }
            for ((hash, element) in pairs) {
                parseMap(element as? JsonObject)?.let { result[hash] = it }
            }
        }
        return result
    }

    /** 下載檔案（例如歌曲的 zip 壓縮檔）。 */
    fun download(url: String): ByteArray {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) throw IOException("下載失敗（HTTP ${connection.responseCode}）")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    // ───────────── 內部工具 ─────────────

    private fun parsePlaylist(id: String, raw: JsonObject): CloudPlaylist {
        val songs = (raw["songs"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val hash = o.str("hash")?.lowercase() ?: return@mapNotNull null
            CloudSong(o.str("key")?.lowercase(), hash, o.str("songName"))
        }
        val title = raw.str("playlistTitle") ?: "BeatSaver 歌單 $id"
        // 確保之後建立的 Quest 歌單檔一定帶有正確的 syncURL
        require(PlaylistLink.idFromSyncUrl((raw["customData"] as? JsonObject)?.str("syncURL")) in setOf(null, id)) {
            "歌單 $id 的 syncURL 和 ID 不一致"
        }
        return CloudPlaylist(id, title, songs, raw)
    }

    private fun parseMap(o: JsonObject?): MapInfo? {
        o ?: return null
        val key = o.str("id") ?: return null
        val version = (o["versions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val metadata = o["metadata"] as? JsonObject
        return MapInfo(
            key = key.lowercase(),
            hash = version.str("hash")?.lowercase() ?: return null,
            downloadUrl = version.str("downloadURL") ?: return null,
            songName = metadata?.str("songName") ?: o.str("name") ?: key,
            mapper = metadata?.str("levelAuthorName"),
        )
    }

    private fun get(url: String): Pair<Int, String?> {
        val connection = open(url)
        try {
            val status = connection.responseCode
            val body = if (status == 200) connection.inputStream.use { it.readBytes().decodeToString() } else null
            return status to body
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "PlaylistMirror/0.1")
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
