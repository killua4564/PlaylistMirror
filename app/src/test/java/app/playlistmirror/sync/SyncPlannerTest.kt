package app.playlistmirror.sync

import app.playlistmirror.data.Playlist
import app.playlistmirror.data.PlaylistEntry
import app.playlistmirror.data.PlaylistLink
import app.playlistmirror.data.Song
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 同步比對規則。對應規格書「核心功能：同步檢查」與測項 T2-xx。
 *
 * 每個測試用三樣東西描述情境：Quest 上的歌單與已安裝的歌、BeatSaver 上的歌單、上次同步記錄，
 * 然後檢查 App 算出的動作對不對。
 */
class SyncPlannerTest {

    private val pid = "100"

    // ───── 建立情境用的小工具 ─────

    private fun song(key: String, hash: String = "h-$key", downloadedAt: Long = 0) =
        Song(hash, File(key), "Song $key", null, null, null, emptyList(), null, downloadedAt, key)

    private fun entry(key: String?, hash: String) = PlaylistEntry(
        hash = hash, songName = "Song $key", key = key,
        raw = buildJsonObject { key?.let { put("key", it) }; put("hash", hash) },
    )

    private fun questPlaylist(vararg entries: PlaylistEntry, id: String? = pid) = Playlist(
        file = File("q-$id.bplist"), title = "Q", entries = entries.toList(),
        raw = buildJsonObject {
            put("playlistTitle", "Q")
            id?.let { put("customData", buildJsonObject { put("syncURL", PlaylistLink.syncUrlFor(it)) }) }
        },
    )

    private fun cloudPlaylist(vararg songs: Pair<String, String>, id: String = pid) =
        CloudPlaylist(id, "Cloud $id", songs.map { (key, hash) -> CloudSong(key, hash, "Song $key") }, JsonObject(emptyMap()))

    private fun record(vararg songs: Triple<String, String, Boolean>, id: String = pid) = SyncRecord(
        mapOf(id to PlaylistRecord(id, songs.associate { (key, hash, installed) ->
            "key:$key" to SongRecord("key:$key", key, hash, installed)
        })),
    )

    private fun plan(
        local: List<Playlist>,
        cloud: Map<String, CloudPlaylist?>,
        installed: List<Song>,
        record: SyncRecord = SyncRecord(),
    ) = SyncPlanner.plan(local, cloud, installed, record)

    private fun List<SyncItem>.kinds() = map { it.kind to it.key }

    // ───── 四類落差 ─────

    @Test
    fun `T2-01 BeatSaver 新加的歌（沒安裝）要下載`() {
        val items = plan(
            local = listOf(questPlaylist()),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a")),
            installed = emptyList(),
            record = record(),
        )
        assertEquals(listOf(SyncKind.Download to "a"), items.kinds())
        assertEquals(false, items.single().needsDecision)
    }

    @Test
    fun `T2-02 你在遊戲裡刪掉的歌，要從 BeatSaver 移除，不能又下載回來`() {
        // 歌單裡還留著這筆紀錄，但歌已經不在 Quest 上
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a")),
            installed = emptyList(),
            record = record(Triple("a", "h-a", true)),
        )
        assertEquals(listOf(SyncKind.RemoveFromBeatSaver to "a"), items.kinds())
    }

    @Test
    fun `T2-03 在 App 裡把歌移出 Quest 歌單，要從 BeatSaver 移除`() {
        val items = plan(
            local = listOf(questPlaylist()),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a")),
            installed = listOf(song("a")),
            record = record(Triple("a", "h-a", true)),
        )
        assertEquals(listOf(SyncKind.RemoveFromBeatSaver to "a"), items.kinds())
    }

    @Test
    fun `T2-04 你在 Quest 新加的歌，要加到 BeatSaver`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = mapOf(pid to cloudPlaylist()),
            installed = listOf(song("a")),
            record = record(),
        )
        assertEquals(listOf(SyncKind.AddToBeatSaver to "a"), items.kinds())
    }

    @Test
    fun `T2-05 BeatSaver 上移除的歌，要從 Quest 歌單移出，歌曲檔案保留`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = mapOf(pid to cloudPlaylist()),
            installed = listOf(song("a")),
            record = record(Triple("a", "h-a", true)),
        )
        assertEquals(listOf(SyncKind.RemoveFromQuestPlaylist to "a"), items.kinds())
    }

    @Test
    fun `T2-06 兩邊一致時不需要做任何事`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"), entry("b", "h-b"))),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a", "b" to "h-b")),
            installed = listOf(song("a"), song("b")),
            record = record(Triple("a", "h-a", true), Triple("b", "h-b", true)),
        )
        assertTrue(items.isEmpty())
    }

    // ───── 歌曲更新 ─────

    @Test
    fun `T2-07 作者發佈新版：用短 ID 認出是同一首歌，列為更新而不是一刪一增`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a-old"))),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a-new")),
            installed = listOf(song("a", "h-a-old")),
            record = record(Triple("a", "h-a-old", true)),
        )
        val item = items.single()
        assertEquals(SyncKind.Update, item.kind)
        assertEquals("h-a-old", item.oldHash)
        assertEquals("h-a-new", item.hash)
    }

    @Test
    fun `T2-08 你刪掉的歌剛好被作者更新：仍判斷為你刪的，從 BeatSaver 移除`() {
        val items = plan(
            local = listOf(questPlaylist()),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a-new")),
            installed = emptyList(),
            record = record(Triple("a", "h-a-old", true)),
        )
        assertEquals(listOf(SyncKind.RemoveFromBeatSaver to "a"), items.kinds())
    }

    // ───── 其他情況 ─────

    @Test
    fun `T2-09 第一次同步沒有記錄：所有落差都列為「請你決定」`() {
        val items = plan(
            local = listOf(questPlaylist(entry("q", "h-q"))),
            cloud = mapOf(pid to cloudPlaylist("c" to "h-c")),
            installed = listOf(song("q")),
            record = SyncRecord(),
        )
        assertEquals(setOf(SyncKind.Download to "c", SyncKind.AddToBeatSaver to "q"), items.kinds().toSet())
        assertTrue(items.all { it.needsDecision })
    }

    @Test
    fun `T2-10 BeatSaver 新加的歌已經安裝過：只加進 Quest 歌單，不重新下載`() {
        val items = plan(
            local = listOf(questPlaylist()),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a")),
            installed = listOf(song("a")),
            record = record(),
        )
        assertEquals(listOf(SyncKind.AddToQuestPlaylist to "a"), items.kinds())
    }

    @Test
    fun `T2-11 歌單裡找不到的歌、BeatSaver 也沒有：清掉這筆紀錄`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = mapOf(pid to cloudPlaylist()),
            installed = emptyList(),
            record = record(),
        )
        assertEquals(listOf(SyncKind.RemoveFromQuestPlaylist to "a"), items.kinds())
    }

    @Test
    fun `T2-12 上次下載失敗（記錄為未安裝）的歌：這次再下載`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a")),
            installed = emptyList(),
            record = record(Triple("a", "h-a", false)),
        )
        assertEquals(listOf(SyncKind.Download to "a"), items.kinds())
    }

    @Test
    fun `T2-13 BeatSaver 上刪掉的歌單：Quest 上也刪除，不刪歌曲、不從 BeatSaver 移除`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = mapOf(pid to null),
            installed = listOf(song("a")),
            record = record(Triple("a", "h-a", true)),
        )
        assertEquals(listOf(SyncKind.DeleteQuestPlaylist to null), items.kinds())
        assertEquals(false, items.single().needsDecision)
    }

    @Test
    fun `T2-13 沒有同步記錄的歌單被判定已刪除：一樣列為刪除，但標「請你決定」`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = mapOf(pid to null),
            installed = listOf(song("a")),
            record = SyncRecord(),
        )
        assertEquals(listOf(SyncKind.DeleteQuestPlaylist to null), items.kinds())
        assertEquals(true, items.single().needsDecision)
    }

    @Test
    fun `T2-14 這次沒查到的歌單（例如網路錯誤）：不做任何判斷`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"))),
            cloud = emptyMap(),
            installed = emptyList(),
            record = record(Triple("a", "h-a", true)),
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `T2-15 BeatSaver 上新建的歌單：建立 Quest 歌單，下載沒裝的歌，舊版的歌列為更新`() {
        val items = plan(
            local = emptyList(),
            cloud = mapOf("200" to cloudPlaylist("a" to "h-a", "b" to "h-b-new", "c" to "h-c", id = "200")),
            installed = listOf(song("b", "h-b-old"), song("c")),
        )
        assertEquals(
            listOf(SyncKind.CreateQuestPlaylist to null, SyncKind.Download to "a", SyncKind.Update to "b"),
            items.kinds(),
        )
    }

    @Test
    fun `T2-16 沒有 syncURL 的歌單不參與同步`() {
        val items = plan(
            local = listOf(questPlaylist(entry("a", "h-a"), id = null)),
            cloud = emptyMap(),
            installed = listOf(song("a")),
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `T2-17 Quest 歌單紀錄沒有短 ID 時，用指紋對到 BeatSaver 的同一首歌`() {
        val items = plan(
            local = listOf(questPlaylist(entry(null, "h-a"))),
            cloud = mapOf(pid to cloudPlaylist("a" to "h-a")),
            installed = listOf(song("a")),
            record = record(Triple("a", "h-a", true)),
        )
        assertTrue(items.isEmpty())
    }

    // ───── 同步後的記錄 ─────

    @Test
    fun `T2-18 沒執行的「從 BeatSaver 移除」保留舊記錄，下次仍判斷為移除，而不是下載`() {
        val local = listOf(questPlaylist())
        val cloud = mapOf(pid to cloudPlaylist("a" to "h-a"))
        val old = record(Triple("a", "h-a", true))
        val pending = plan(local, cloud, emptyList(), old)

        val next = SyncPlanner.nextRecord(old, local, cloud, emptyList(), pending, now = 1)

        assertEquals(listOf(SyncKind.RemoveFromBeatSaver to "a"), plan(local, cloud, emptyList(), next).kinds())
    }

    @Test
    fun `T2-19 同步完成後，兩邊都有的歌記為已同步，並記下是否已安裝`() {
        val local = listOf(questPlaylist(entry("a", "h-a"), entry("b", "h-b")))
        val cloud = mapOf(pid to cloudPlaylist("a" to "h-a", "b" to "h-b"))
        val next = SyncPlanner.nextRecord(SyncRecord(), local, cloud, listOf(song("a")), emptyList(), now = 42)

        val songs = next.playlists.getValue(pid).songs
        assertEquals(setOf("key:a", "key:b"), songs.keys)
        assertEquals(true, songs.getValue("key:a").installed)
        assertEquals(false, songs.getValue("key:b").installed)
        assertEquals(42L, next.syncedAt)
    }

    @Test
    fun `T2-19 BeatSaver 上刪掉的歌單，記錄一併清除；沒查到的歌單，記錄保留`() {
        val old = SyncRecord(mapOf(
            "1" to PlaylistRecord("1", emptyMap()),
            "2" to PlaylistRecord("2", emptyMap()),
        ))
        val next = SyncPlanner.nextRecord(old, emptyList(), mapOf("1" to null), emptyList(), emptyList(), now = 1)
        assertEquals(setOf("2"), next.playlists.keys)
    }

    @Test
    fun `身分：有短 ID 用短 ID，沒有才用指紋`() {
        assertEquals("key:3b2c", songIdentity("3B2C", "abc"))
        assertEquals("hash:abc", songIdentity(null, "ABC"))
        assertEquals(null, songIdentity(null, null))
    }
}
