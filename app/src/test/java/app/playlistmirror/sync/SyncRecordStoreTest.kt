package app.playlistmirror.sync

import app.playlistmirror.data.PlaylistLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * 同步記錄的存檔，以及從 syncURL 取歌單 ID。對應測項 T2-20、T2-21。
 */
class SyncRecordStoreTest {

    private val dir = Files.createTempDirectory("sync-record").toFile()

    @Test
    fun `T2-20 存檔後讀回，內容完全相同`() {
        val store = SyncRecordStore(dir.resolve("record.json"))
        val record = SyncRecord(
            mapOf("100" to PlaylistRecord("100", mapOf(
                "key:a" to SongRecord("key:a", "a", "h-a", true),
                "hash:h-b" to SongRecord("hash:h-b", null, "h-b", false),
            ))),
            syncedAt = 1234,
        )
        store.save(record)
        assertEquals(record, store.load())
    }

    @Test
    fun `T2-20 沒有記錄或記錄壞掉時，當作從沒同步過`() {
        assertEquals(SyncRecord(), SyncRecordStore(dir.resolve("none.json")).load())
        val broken = dir.resolve("broken.json").apply { writeText("{ 壞掉") }
        assertEquals(SyncRecord(), SyncRecordStore(broken).load())
    }

    @Test
    fun `T2-21 從 syncURL 取出 BeatSaver 歌單 ID`() {
        assertEquals("12345", PlaylistLink.idFromSyncUrl("https://api.beatsaver.com/playlists/id/12345/download"))
        assertEquals("12345", PlaylistLink.idFromSyncUrl("https://beatsaver.com/playlists/id/12345"))
        assertNull(PlaylistLink.idFromSyncUrl("https://example.com/playlists/id/12345/download"))
        assertNull(PlaylistLink.idFromSyncUrl(null))
        assertEquals("https://api.beatsaver.com/playlists/id/7/download", PlaylistLink.syncUrlFor("7"))
    }
}
