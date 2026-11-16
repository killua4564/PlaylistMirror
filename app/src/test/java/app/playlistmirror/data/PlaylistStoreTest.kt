package app.playlistmirror.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 歌單檔的讀寫。對應測項 T1-04、T1-17、T1-19、T1-20、T1-24。
 */
class PlaylistStoreTest {

    private val game = FakeGame()
    private val store = PlaylistStore(game.playlistsDir)

    @After
    fun tearDown() = game.cleanUp()

    @Test
    fun `T1-04 讀取時指紋一律轉成小寫`() {
        game.addPlaylist("a.bplist", "A", listOf("ABCDEF0123" to "Song"))
        val playlist = store.parse(File(game.playlistsDir, "a.bplist"))
        assertEquals("abcdef0123", playlist.entries.single().hash)
    }

    @Test
    fun `沒有標題時用檔名代替；遊戲內建歌曲沒有指紋也能讀`() {
        File(game.playlistsDir, "untitled.json").writeText("""{"songs":[{"songName":"Built-in"}]}""")
        val playlist = store.parse(File(game.playlistsDir, "untitled.json"))
        assertEquals("untitled", playlist.title)
        assertNull(playlist.entries.single().hash)
    }

    @Test
    fun `T1-17 壞掉的歌單列為讀不到，其他歌單照常；隱藏檔和其他副檔名不讀`() {
        game.addPlaylist("good.bplist", "Good", emptyList())
        File(game.playlistsDir, "broken.bplist").writeText("{ 壞掉")
        File(game.playlistsDir, ".hidden.bplist").writeText("{}")
        File(game.playlistsDir, "notes.txt").writeText("hello")

        val (playlists, broken) = store.loadAll()

        assertEquals(listOf("Good"), playlists.map { it.title })
        assertEquals(listOf("broken.bplist"), broken.map { it.file.name })
    }

    @Test
    fun `T1-19 存檔只換掉歌曲清單，其他欄位與每首歌的額外資料原封不動`() {
        val original = """
            {"playlistTitle":"workout","playlistAuthor":"tester","playlistDescription":"說明",
             "customData":{"syncURL":"https://api.beatsaver.com/playlists/id/123/download","otherMod":true},
             "image":"/9j/4AAQSkZJRgABAQ==",
             "songs":[{"key":"1a2b","hash":"aaa","songName":"Neon","difficulties":[{"name":"Expert"}]}],
             "futureField":[1,2,3]}
        """.trimIndent()
        val file = File(game.playlistsDir, "workout.json").apply { writeText(original) }
        val playlist = store.parse(file)
        val song = Song("bbb", File("x"), "New", null, null, null, emptyList(), null, 0, "9f")

        store.save(playlist, playlist.entries + store.newEntry(song))

        val before = Json.parseToJsonElement(original).jsonObject
        val after = game.readPlaylist("workout.json")
        // 除了 songs 以外，每個欄位都和原本一模一樣，順序也一樣
        assertEquals(before.keys.toList(), after.keys.toList())
        for (key in before.keys - "songs") assertEquals("欄位 $key 被改動了", before[key], after[key])
        // 原本那首歌的難度設定還在，新歌加在最後
        val songs = after["songs"]!!.jsonArray
        assertEquals(before["songs"]!!.jsonArray[0], songs[0])
        assertEquals("bbb", songs[1].jsonObject["hash"]!!.jsonPrimitive.content)
        assertEquals("9f", songs[1].jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `T2-13 刪除歌單只刪歌單檔，其他歌單照常`() {
        val gone = game.addPlaylist("gone.bplist", "Gone", listOf("aaa" to "Song"))
        game.addPlaylist("keep.bplist", "Keep", listOf("aaa" to "Song"))
        store.delete(store.parse(gone))
        assertTrue(!gone.exists())
        assertEquals(listOf("Keep"), store.loadAll().first.map { it.title })
    }

    @Test
    fun `T1-20 寫檔失敗時，原本的歌單檔完整保留`() {
        val file = game.addPlaylist("a.bplist", "A", listOf("aaa" to "Song"))
        val before = file.readText()
        val playlist = store.parse(file)

        // 讓資料夾變成唯讀，模擬寫檔失敗（例如儲存空間出問題）
        game.playlistsDir.setWritable(false)
        try {
            store.save(playlist, emptyList())
            fail("寫檔失敗時應該要回報錯誤")
        } catch (_: Exception) {
            // 預期會失敗
        } finally {
            game.playlistsDir.setWritable(true)
        }

        assertEquals(before, file.readText())
        assertTrue("不應留下暫存檔", game.playlistsDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `新紀錄在不知道短 ID 時就不寫 key`() {
        val song = Song("ccc", File("x"), "NoKey", null, null, null, emptyList(), null, 0, null)
        val entry = store.newEntry(song)
        assertEquals(setOf("hash", "songName"), entry.raw.keys)
        assertEquals(JsonPrimitive("ccc"), entry.raw["hash"])
    }

    @Test
    fun `T1-24 讀出歌單封面（image 或 imageString），去掉 data 開頭；沒有封面就是 null`() {
        File(game.playlistsDir, "plain.bplist").writeText("""{"playlistTitle":"P","image":"/9j/AAAA","songs":[]}""")
        assertEquals("/9j/AAAA", store.parse(File(game.playlistsDir, "plain.bplist")).imageBase64)
        File(game.playlistsDir, "uri.bplist").writeText("""{"playlistTitle":"U","image":"data:image/png;base64,iVBOR","songs":[]}""")
        assertEquals("iVBOR", store.parse(File(game.playlistsDir, "uri.bplist")).imageBase64)
        // .json 歌單用 imageString 存封面
        File(game.playlistsDir, "qsm.json").writeText("""{"playlistTitle":"Q","imageString":"iVBORw0K","songs":[]}""")
        assertEquals("iVBORw0K", store.parse(File(game.playlistsDir, "qsm.json")).imageBase64)
        File(game.playlistsDir, "none.bplist").writeText("""{"playlistTitle":"N","songs":[]}""")
        assertNull(store.parse(File(game.playlistsDir, "none.bplist")).imageBase64)
    }
}
