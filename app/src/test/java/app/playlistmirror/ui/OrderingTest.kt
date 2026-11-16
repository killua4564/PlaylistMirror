package app.playlistmirror.ui

import app.playlistmirror.data.Playlist
import app.playlistmirror.data.Song
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 排序與顯示格式。對應測項 T1-09、T1-16、T1-03（顯示）。
 */
class OrderingTest {

    private fun song(title: String, downloadedAt: Long) =
        Song(title.hashCode().toString(), File(title), title, null, null, null, emptyList(), null, downloadedAt, null)

    private val songs = listOf(
        song("apple pie", 300),
        song("Zebra Run", 100),
        song("夜に駆ける", 200),
        song("Brand New", 400),
    )

    @Test
    fun `T1-09 下載日期新到舊`() {
        assertEquals(
            listOf("Brand New", "apple pie", "夜に駆ける", "Zebra Run"),
            songs.sortedWith(SongSort.NewestFirst.comparator).map { it.title },
        )
    }

    @Test
    fun `T1-09 下載日期舊到新`() {
        assertEquals(
            listOf("Zebra Run", "夜に駆ける", "apple pie", "Brand New"),
            songs.sortedWith(SongSort.OldestFirst.comparator).map { it.title },
        )
    }

    @Test
    fun `T1-09 歌名排序不分大小寫，非英文在最後`() {
        assertEquals(
            listOf("apple pie", "Brand New", "Zebra Run", "夜に駆ける"),
            songs.sortedWith(SongSort.Title.comparator).map { it.title },
        )
    }

    @Test
    fun `T1-09 只差大小寫的歌名，排序結果固定`() {
        val names = listOf(song("apple", 1), song("Apple", 1), song("APPLE", 1))
        val once = names.sortedWith(SongSort.Title.comparator).map { it.title }
        val again = names.reversed().sortedWith(SongSort.Title.comparator).map { it.title }
        assertEquals(once, again)
    }

    @Test
    fun `T1-09 下載日期相同時，再依歌名排序`() {
        val same = listOf(song("b", 1), song("a", 1))
        assertEquals(listOf("a", "b"), same.sortedWith(SongSort.NewestFirst.comparator).map { it.title })
    }

    @Test
    fun `T1-16 歌單依名稱排序，不分大小寫`() {
        fun playlist(title: String) = Playlist(File("$title.bplist"), title, emptyList(), JsonObject(emptyMap()))
        val sorted = listOf(playlist("workout"), playlist("Anime"), playlist("VOCALOID"), playlist("anisong"))
            .sortedWith(PlaylistTitleOrder).map { it.title }
        assertEquals(listOf("Anime", "anisong", "VOCALOID", "workout"), sorted)
    }

    @Test
    fun `T1-03 時間長度顯示為 分：秒，沒有資料顯示破折號`() {
        assertEquals("3:05", formatDuration(185))
        assertEquals("0:09", formatDuration(9))
        assertEquals("—", formatDuration(null))
    }
}
