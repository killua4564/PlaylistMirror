package app.playlistmirror.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 用從 Quest 拉出來的真實資料測試。對應測項 T1-01、T1-05、T1-19。
 *
 * 真實資料有著作權（別人的歌曲與譜面）也含個人收藏，所以不放進 GitHub。
 * 設定檔放在專案根目錄的 testdata-local/real-data.json（已被 .gitignore 排除）。
 * 沒有這個檔案的電腦上，這些測試會自動「略過」，不算失敗。
 */
class RealDataTest {

    private lateinit var backupDir: File
    private lateinit var expected: Map<String, String>
    private lateinit var game: FakeGame

    @Before
    fun setUp() {
        // 單元測試執行時的工作目錄是 app/，設定檔在上一層
        val config = listOf(File("../testdata-local/real-data.json"), File("testdata-local/real-data.json"))
            .firstOrNull { it.isFile }
        assumeTrue("沒有真實資料設定檔，略過", config != null)
        val json = Json.parseToJsonElement(config!!.readText()).jsonObject
        backupDir = File(json["backupDir"]!!.jsonPrimitive.content)
        assumeTrue("找不到真實資料資料夾，略過", backupDir.isDirectory)
        expected = json["expectedHashes"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        game = FakeGame()
    }

    @After
    fun tearDown() {
        if (::game.isInitialized) game.cleanUp()
    }

    @Test
    fun `T1-05 真實歌曲算出的指紋，和 BeatSaver 上的完全一致`() {
        for ((folderName, expectedHash) in expected) {
            val folder = File(backupDir, folderName)
            val info = File(folder, "Info.dat")
            val parsed = SongInfoParser.parse(info.readText())
            assertEquals("「$folderName」的指紋不一致", expectedHash, SongHasher.hash(folder, info, parsed.filesToHash))
        }
    }

    @Test
    fun `T1-01 真實歌曲全部讀得到，並和真實歌單正確對上`() {
        // 複製到暫存的假遊戲資料夾（只複製說明書和譜面，不複製音樂和封面），不動到原始備份
        val songFolders = backupDir.listFiles()!!.filter { it.isDirectory }
        for (folder in songFolders) {
            val target = File(game.songsDir, folder.name).apply { mkdirs() }
            folder.listFiles()!!.filter { it.extension == "dat" }.forEach { it.copyTo(File(target, it.name)) }
        }
        val playlists = backupDir.listFiles()!!.filter { it.extension == "bplist" }
        playlists.forEach { it.copyTo(File(game.playlistsDir, it.name)) }

        val state = game.repository().also { runBlocking { it.reload() } }.state.value

        assertEquals(songFolders.size, state.songs.size)
        assertEquals(emptyList<BrokenFile>(), state.brokenSongs)
        assertEquals(playlists.size, state.playlists.size)
        // 每首歌單裡的歌：有安裝的就要對得上（對不上代表指紋或大小寫處理有問題）
        val installed = state.songsByHash.keys
        val expectedInstalled = expected.values.toSet()
        for (playlist in state.playlists) {
            for (hash in playlist.hashes) {
                assertEquals("歌單「${playlist.title}」的 $hash", hash in expectedInstalled, hash in installed)
            }
        }
    }

    @Test
    fun `T1-19 真實歌單存檔後，除了歌曲清單之外完全不變`() {
        val source = backupDir.listFiles()!!.filter { it.extension == "bplist" }
        for (file in source) {
            val copy = file.copyTo(File(game.playlistsDir, file.name))
            val store = PlaylistStore(game.playlistsDir)
            val playlist = store.parse(copy)

            // 移除第一首再存檔
            store.save(playlist, playlist.entries.drop(1))

            val before = Json.parseToJsonElement(file.readText()).jsonObject
            val after = Json.parseToJsonElement(copy.readText()) as JsonObject
            assertEquals(before.keys.toList(), after.keys.toList())
            for (key in before.keys - "songs") assertEquals("「${file.name}」的 $key 被改動了", before[key], after[key])
        }
    }
}
