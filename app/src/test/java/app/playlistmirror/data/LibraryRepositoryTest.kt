package app.playlistmirror.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 歌曲庫管理員：掃描、指紋備忘錄、管理歌單、刪除歌曲。
 * 對應測項 T1-01、T1-02、T1-04、T1-06、T1-08、T1-11、T1-14、T1-18。
 */
class LibraryRepositoryTest {

    private val game = FakeGame()

    @After
    fun tearDown() = game.cleanUp()

    private fun loaded(): LibraryRepository = game.repository().also { runBlocking { it.reload() } }

    @Test
    fun `T1-01 新舊格式的歌都讀得到，指紋正確`() {
        val v2 = game.addV2Song("1a2b (Neon - A)", "Neon", "A", listOf("Easy", "Expert"))
        val v4 = game.addV4Song("6f70 (Future - B)", "Future", "B", listOf("Hard"), 183.4)

        val songs = loaded().state.value.songsByHash

        assertEquals(setOf(v2, v4), songs.keys)
        assertEquals("Neon", songs[v2]!!.title)
        assertEquals(listOf(Difficulty.Easy, Difficulty.Expert), songs[v2]!!.difficulties)
        assertEquals("1a2b", songs[v2]!!.key)
        assertEquals(183, songs[v4]!!.durationSeconds)
    }

    @Test
    fun `T1-01 舊的 SongLoader 資料夾也會讀；同一首歌出現兩次只算一次`() {
        val hash = game.addV2Song("1a2b (Neon - A)", "Neon", "A", listOf("Expert"))
        val legacy = File(game.root, GamePaths.SONG_LOADER).apply { mkdirs() }
        game.songsDir.resolve("1a2b (Neon - A)").copyRecursively(legacy.resolve("1a2b (Neon - A)"))
        game.addV2Song("2b3c (Other - B)", "Other", "B", listOf("Hard"))

        val songs = loaded().state.value.songs

        assertEquals(2, songs.size)
        assertEquals(1, songs.count { it.hash == hash })
    }

    @Test
    fun `T1-02 沒有 Info_dat 的資料夾略過，壞掉的列為讀不到，其他歌照常`() {
        game.addV2Song("1a2b (Neon - A)", "Neon", "A", listOf("Expert"))
        game.songsDir.resolve("not-a-song").apply { mkdirs() }.resolve("readme.txt").writeText("hi")
        game.songsDir.resolve("7a8b (Broken - X)").apply { mkdirs() }.resolve("Info.dat").writeText("{ 壞掉")

        val state = loaded().state.value

        assertEquals(listOf("Neon"), state.songs.map { it.title })
        assertEquals(listOf("7a8b (Broken - X)"), state.brokenSongs.map { it.file.name })
    }

    @Test
    fun `T1-04 與 T1-08 歌單認得已安裝的歌（指紋大小寫不同也行），並算出不在任何歌單的歌`() {
        val inList = game.addV2Song("1a2b (Neon - A)", "Neon", "A", listOf("Expert"))
        val notInList = game.addV2Song("2b3c (Apple - B)", "Apple", "B", listOf("Hard"))
        game.addPlaylist("a.bplist", "A", listOf(inList.uppercase() to "Neon", "0".repeat(40) to "Missing"))

        val state = loaded().state.value

        assertTrue(inList in state.listedHashes)
        assertEquals(listOf(notInList), state.songs.filter { it.hash !in state.listedHashes }.map { it.hash })
    }

    @Test
    fun `T1-06 第二次讀取沿用指紋備忘錄；Info_dat 改過就重算`() {
        val hash = game.addV2Song("1a2b (Neon - A)", "Neon", "A", listOf("Expert"))
        loaded()
        val folder = game.songsDir.resolve("1a2b (Neon - A)")

        // 刪掉譜面檔：如果重算，指紋會改變；指紋沒變就代表用了備忘錄
        folder.resolve("ExpertStandard.dat").delete()
        assertEquals(setOf(hash), loaded().state.value.songsByHash.keys)

        // 改動 Info.dat：備忘錄失效，必須重算
        val info = folder.resolve("Info.dat")
        info.writeText(info.readText().replace("Neon", "Neon2"))
        info.setLastModified(info.lastModified() + 5_000)
        val newHash = loaded().state.value.songs.single().hash
        assertFalse(newHash == hash)
        assertEquals("Neon2", loaded().state.value.songs.single().title)
    }

    @Test
    fun `T1-11 管理歌單：一次加入與移出，只改有變動的歌單`() {
        val hash = game.addV2Song("1a2b (Neon - A)", "Neon", "A", listOf("Expert"))
        game.addPlaylist("a.bplist", "A", listOf(hash to "Neon"))
        game.addPlaylist("b.bplist", "B", emptyList())
        val untouched = game.addPlaylist("c.bplist", "C", emptyList())
        val untouchedText = untouched.readText()
        val repo = loaded()
        val song = repo.state.value.songs.single()
        val b = repo.state.value.playlists.first { it.title == "B" }

        // 從 A 移出、加入 B、C 維持不在
        runBlocking { repo.setSongPlaylists(song, setOf(b.file.absolutePath)) }

        assertTrue(game.readPlaylist("a.bplist")["songs"]!!.jsonArray.isEmpty())
        assertEquals(hash, game.readPlaylist("b.bplist")["songs"]!!.jsonArray.single().jsonObject["hash"]!!.jsonPrimitive.content)
        assertEquals("沒變動的歌單檔不該被重寫", untouchedText, untouched.readText())
        // 畫面用的狀態也同步更新
        val state = repo.state.value
        assertEquals(listOf("B"), state.playlists.filter { hash in it.hashes }.map { it.title })
    }

    @Test
    fun `T1-14 刪除歌曲：資料夾刪除，並從所有歌單移除`() {
        val hash = game.addV2Song("1a2b (Neon - A)", "Neon", "A", listOf("Expert"))
        val keep = game.addV2Song("2b3c (Apple - B)", "Apple", "B", listOf("Hard"))
        game.addPlaylist("a.bplist", "A", listOf(hash to "Neon", keep to "Apple"))
        game.addPlaylist("b.bplist", "B", listOf(hash to "Neon"))
        val repo = loaded()
        val song = repo.state.value.songsByHash[hash]!!

        runBlocking { repo.deleteSong(song) }

        assertFalse(game.songsDir.resolve("1a2b (Neon - A)").exists())
        assertEquals(listOf(keep), game.readPlaylist("a.bplist")["songs"]!!.jsonArray.map { it.jsonObject["hash"]!!.jsonPrimitive.content })
        assertTrue(game.readPlaylist("b.bplist")["songs"]!!.jsonArray.isEmpty())
        assertEquals(listOf(keep), repo.state.value.songs.map { it.hash })
    }

    @Test
    fun `T1-18 未安裝的歌也能從歌單移出`() {
        val missing = "0".repeat(40)
        game.addPlaylist("a.bplist", "A", listOf(missing to "Missing"))
        val repo = loaded()
        val playlist = repo.state.value.playlists.single()

        runBlocking { repo.removeEntry(playlist, playlist.entries.single()) }

        assertTrue(game.readPlaylist("a.bplist")["songs"]!!.jsonArray.isEmpty())
    }
}
