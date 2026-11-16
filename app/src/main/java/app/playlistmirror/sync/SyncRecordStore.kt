package app.playlistmirror.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 把「上次同步的記錄」存在 App 自己的私人空間（不會寫進遊戲資料夾）。
 *
 * 寫檔一樣先寫暫存檔再換掉，避免寫到一半壞掉；
 * 檔案壞掉時當作「從沒同步過」，下次同步會請使用者決定落差，而不是亂判斷。
 */
class SyncRecordStore(private val file: File) {

    fun load(): SyncRecord {
        if (!file.isFile) return SyncRecord()
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val playlists = (root["playlists"] as? JsonObject).orEmpty().mapValues { (id, value) ->
                val songs = ((value as? JsonObject)?.get("songs") as? JsonArray).orEmpty().mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val identity = o.str("identity") ?: return@mapNotNull null
                    val hash = o.str("hash") ?: return@mapNotNull null
                    SongRecord(identity, o.str("key"), hash, (o["installed"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
                PlaylistRecord(id, songs.associateBy { it.identity })
            }
            SyncRecord(playlists, (root["syncedAt"] as? JsonPrimitive)?.longOrNull)
        } catch (_: Exception) {
            SyncRecord()
        }
    }

    fun save(record: SyncRecord) {
        val root = buildJsonObject {
            record.syncedAt?.let { put("syncedAt", it) }
            put("playlists", buildJsonObject {
                for ((id, playlist) in record.playlists) {
                    put(id, buildJsonObject {
                        put("songs", buildJsonArray {
                            for (song in playlist.songs.values) {
                                add(buildJsonObject {
                                    put("identity", song.identity)
                                    song.key?.let { put("key", it) }
                                    put("hash", song.hash)
                                    put("installed", song.installed)
                                })
                            }
                        })
                    })
                }
            })
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IllegalStateException("無法寫入同步記錄")
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
