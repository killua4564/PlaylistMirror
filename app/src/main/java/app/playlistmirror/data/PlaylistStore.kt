package app.playlistmirror.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 讀寫遊戲的歌單檔（放在 Mods/PlaylistManager/Playlists 資料夾，副檔名 .bplist 或 .json）。
 *
 * 最重要的原則：**只改我們要改的部分，其他內容原封不動**。
 * 歌單檔裡可能有封面圖、說明、其他模組的設定，我們不認得也不能弄丟。
 */
class PlaylistStore(private val folder: File) {

    private val json = Json { prettyPrint = true }

    /** 讀取資料夾裡所有歌單。讀不懂的檔案放進「壞掉清單」，不讓整個 App 失敗。 */
    fun loadAll(): Pair<List<Playlist>, List<BrokenFile>> {
        val playlists = mutableListOf<Playlist>()
        val broken = mutableListOf<BrokenFile>()
        val files = folder.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") }
            .filter { it.extension.lowercase() in setOf("bplist", "json") }
        for (file in files) {
            try {
                playlists += parse(file)
            } catch (e: Exception) {
                broken += BrokenFile(file, e.message ?: "無法讀取")
            }
        }
        return playlists to broken
    }

    fun parse(file: File): Playlist {
        val raw = Json.parseToJsonElement(file.readText()).jsonObject
        val entries = raw.arr("songs").mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            PlaylistEntry(
                hash = o.str("hash")?.lowercase(),
                songName = o.str("songName"),
                raw = o,
                key = o.str("key").blankToNull()?.lowercase(),
            )
        }
        val title = raw.str("playlistTitle").blankToNull() ?: file.nameWithoutExtension
        return Playlist(file, title, entries, raw)
    }

    /**
     * 用新的歌曲清單覆寫歌單檔。
     * 只替換 "songs" 這一欄，其他欄位保持原本的內容和順序。
     */
    fun save(playlist: Playlist, newEntries: List<PlaylistEntry>): Playlist {
        val songs = JsonArray(newEntries.map { it.raw })
        val newRaw = buildJsonObject {
            var placed = false
            for ((key, value) in playlist.raw) {
                if (key == "songs") {
                    put(key, songs)
                    placed = true
                } else {
                    put(key, value)
                }
            }
            if (!placed) put("songs", songs)
        }
        writeSafely(playlist.file, json.encodeToString(JsonElement.serializer(), newRaw))
        return playlist.copy(entries = newEntries, raw = newRaw)
    }

    /**
     * 用 BeatSaver 下載的歌單內容，在 Quest 建立一個新的歌單檔（同步時「BeatSaver 上新建的歌單」用）。
     * 檔名用歌單名稱，遇到同名就加上編號。內容原封不動寫入，包含封面和 syncURL。
     */
    fun createFromRaw(title: String, raw: JsonObject): Playlist {
        folder.mkdirs()
        val base = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "playlist" }
        var file = File(folder, "$base.bplist")
        var n = 2
        while (file.exists()) {
            file = File(folder, "$base ($n).bplist")
            n++
        }
        writeSafely(file, json.encodeToString(JsonElement.serializer(), raw))
        return parse(file)
    }

    /** 整份替換歌單檔內容（同步時「解除對應」用：拿掉 syncURL，其他保留）。 */
    fun saveRaw(playlist: Playlist, raw: JsonObject): Playlist {
        writeSafely(playlist.file, json.encodeToString(JsonElement.serializer(), raw))
        return parse(playlist.file)
    }

    /** 把一首已安裝的歌，做成一筆可以放進歌單的紀錄。 */
    fun newEntry(song: Song): PlaylistEntry {
        val raw = buildJsonObject {
            song.key?.let { put("key", it) }
            put("hash", song.hash)
            put("songName", song.title)
        }
        return PlaylistEntry(song.hash, song.title, raw, song.key)
    }

    /**
     * 安全寫檔：先寫到暫存檔，成功後再換掉原檔。
     * 這樣即使寫到一半出錯（例如沒電），原本的歌單也不會變成只寫一半的壞檔。
     */
    private fun writeSafely(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // 有些系統不允許直接覆蓋，改成先刪掉舊檔再改名
            target.delete()
            if (!temp.renameTo(target)) throw IllegalStateException("無法寫入歌單檔：${target.name}")
        }
    }
}
