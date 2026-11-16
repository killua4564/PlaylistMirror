package app.playlistmirror.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 歌曲指紋與時間長度的「備忘錄」。
 *
 * 為什麼需要：算指紋要把每首歌的檔案整個讀一遍，幾百首歌會很慢。
 * 所以第一次算完就記下來，下次打開 App 時，只要歌曲沒變，就直接拿備忘錄裡的答案。
 *
 * 怎麼判斷「歌曲沒變」：記下 Info.dat 的修改時間和大小（stamp），兩者一樣就沿用。
 *
 * 備忘錄存在 App 自己的私人空間，不會寫進遊戲的資料夾（原作者的 App 會寫進去，我們不這樣做）。
 */
class SongCache(private val file: File) {

    data class Entry(val stamp: String, val hash: String, val durationSeconds: Int?)

    private val entries = mutableMapOf<String, Entry>()

    /** 從檔案讀回備忘錄；檔案不存在或壞掉就當作空白備忘錄。 */
    fun load() {
        entries.clear()
        if (!file.isFile) return
        try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            for ((path, value) in root) {
                val o = value as? JsonObject ?: continue
                val stamp = o.str("stamp") ?: continue
                val hash = o.str("hash") ?: continue
                val duration = (o["duration"] as? JsonPrimitive)?.intOrNull
                entries[path] = Entry(stamp, hash, duration)
            }
        } catch (_: Exception) {
            entries.clear()
        }
    }

    /** 查備忘錄：同一個資料夾、且 stamp 一樣，才算有效。 */
    fun get(folderPath: String, stamp: String): Entry? =
        entries[folderPath]?.takeIf { it.stamp == stamp }

    fun put(folderPath: String, entry: Entry) {
        entries[folderPath] = entry
    }

    /** 存回檔案。只保留目前還存在的歌曲，已刪除的歌不留在備忘錄裡。 */
    fun save(keepPaths: Set<String>) {
        val root = buildJsonObject {
            for ((path, entry) in entries) {
                if (path !in keepPaths) continue
                put(path, buildJsonObject {
                    put("stamp", entry.stamp)
                    put("hash", entry.hash)
                    entry.durationSeconds?.let { put("duration", it) }
                })
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
