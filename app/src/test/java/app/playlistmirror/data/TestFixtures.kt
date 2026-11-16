package app.playlistmirror.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 測試用的「假遊戲資料夾」產生器。
 *
 * 每個測試都在電腦的暫存區建一個全新的資料夾，結構和 Quest 上一樣，
 * 測完就刪掉，所以測試之間不會互相影響，也不會碰到真正的遊戲資料。
 */
class FakeGame {
    val root: File = Files.createTempDirectory("playlistmirror-test").toFile()
    val songsDir = File(root, GamePaths.SONG_CORE).apply { mkdirs() }
    val playlistsDir = File(root, GamePaths.PLAYLISTS).apply { mkdirs() }
    val cacheFile = File(root, "app-private/song-cache.json")

    fun repository() = LibraryRepository(root, cacheFile)

    /** 建立一首舊格式（v2）歌曲，回傳它的指紋。 */
    fun addV2Song(folderName: String, title: String, mapper: String, difficulties: List<String>): String {
        val folder = File(songsDir, folderName).apply { mkdirs() }
        val beatmaps = difficulties.map { diff ->
            val file = "${diff}Standard.dat"
            File(folder, file).writeText("""{"_version":"2.2.0","_notes":[],"title":"$title $diff"}""")
            buildJsonObject {
                put("_difficulty", diff)
                put("_beatmapFilename", file)
            }
        }
        val info = buildJsonObject {
            put("_version", "2.0.0")
            put("_songName", title)
            put("_songSubName", "")
            put("_songAuthorName", "Artist of $title")
            put("_levelAuthorName", mapper)
            put("_songFilename", "song.egg")
            put("_difficultyBeatmapSets", JsonArray(listOf(buildJsonObject {
                put("_beatmapCharacteristicName", "Standard")
                put("_difficultyBeatmaps", JsonArray(beatmaps))
            })))
        }
        File(folder, "Info.dat").writeText(info.toString())
        return sha1(folder, listOf("Info.dat") + difficulties.map { "${it}Standard.dat" })
    }

    /** 建立一首新格式（v4）歌曲，回傳它的指紋（含音訊資料檔與燈光檔）。 */
    fun addV4Song(folderName: String, title: String, mapper: String, difficulties: List<String>, duration: Double): String {
        val folder = File(songsDir, folderName).apply { mkdirs() }
        File(folder, "AudioData.dat").writeText("""{"version":"4.0.0","bpmData":[]}""")
        val files = mutableListOf("AudioData.dat")
        val beatmaps = difficulties.map { diff ->
            val beatmap = "$diff.beatmap.dat"
            val lightshow = "$diff.lightshow.dat"
            File(folder, beatmap).writeText("""{"version":"4.0.0","colorNotes":[],"d":"$diff"}""")
            File(folder, lightshow).writeText("""{"version":"4.0.0","basicEvents":[],"d":"$diff"}""")
            files += listOf(beatmap, lightshow)
            buildJsonObject {
                put("characteristic", "Standard")
                put("difficulty", diff)
                put("beatmapAuthors", buildJsonObject {
                    put("mappers", JsonArray(listOf(JsonPrimitive(mapper))))
                })
                put("beatmapDataFilename", beatmap)
                put("lightshowDataFilename", lightshow)
            }
        }
        val info = buildJsonObject {
            put("version", "4.0.1")
            put("song", buildJsonObject {
                put("title", title)
                put("author", "Artist of $title")
            })
            put("audio", buildJsonObject {
                put("songFilename", "song.ogg")
                put("songDuration", duration)
                put("audioDataFilename", "AudioData.dat")
            })
            put("difficultyBeatmaps", JsonArray(beatmaps))
        }
        File(folder, "Info.dat").writeText(info.toString())
        return sha1(folder, listOf("Info.dat") + files)
    }

    /** 建立一個歌單檔。songs 是（指紋, 歌名）清單；extra 可以放額外的欄位。 */
    fun addPlaylist(fileName: String, title: String, songs: List<Pair<String, String>>, extra: Map<String, JsonElement> = emptyMap()): File {
        val json = buildJsonObject {
            put("playlistTitle", title)
            put("playlistAuthor", "tester")
            extra.forEach { (k, v) -> put(k, v) }
            put("songs", JsonArray(songs.map { (hash, name) ->
                buildJsonObject {
                    put("hash", hash)
                    put("songName", name)
                }
            }))
        }
        return File(playlistsDir, fileName).apply { writeText(json.toString()) }
    }

    fun readPlaylist(fileName: String): JsonObject =
        Json.parseToJsonElement(File(playlistsDir, fileName).readText()) as JsonObject

    fun cleanUp() {
        root.deleteRecursively()
    }

    companion object {
        /** 測試自己用的指紋計算（和 App 分開寫），用來核對 App 算得對不對。 */
        fun sha1(folder: File, files: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-1")
            files.forEach { digest.update(File(folder, it).readBytes()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
