package app.playlistmirror.ui

import app.playlistmirror.data.Playlist
import app.playlistmirror.data.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 這個檔案集中放「怎麼顯示」和「怎麼排序」的規則，要改規則只要改這裡。

/** 秒數變成「分:秒」，例如 185 → 3:05。沒有資料顯示「—」。 */
fun formatDuration(seconds: Int?): String =
    seconds?.let { "%d:%02d".format(it / 60, it % 60) } ?: "—"

/** 時間變成「年/月/日」。 */
fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(millis))

/**
 * 名稱排序：不分大小寫的字元順序。
 * 「apple」和「Apple」視為相同位置，所以「apple pie」會排在「Zebra Run」前面；
 * 日文、中文等非英文字會排在英文之後。
 * 兩個名稱只差大小寫時，再用原本的字元順序決定先後，讓結果固定不跳動。
 */
val NameOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER.then(naturalOrder())

val SongTitleOrder: Comparator<Song> = compareBy(NameOrder) { it: Song -> it.title }.thenBy { it.hash }
val PlaylistTitleOrder: Comparator<Playlist> =
    compareBy(NameOrder) { it: Playlist -> it.title }.thenBy { it.file.name }

/** 歌曲清單的排序選項。 */
enum class SongSort(val label: String, val comparator: Comparator<Song>) {
    NewestFirst("下載日期（新到舊）", compareByDescending<Song> { it.downloadedAt }.then(SongTitleOrder)),
    OldestFirst("下載日期（舊到新）", compareBy<Song> { it.downloadedAt }.then(SongTitleOrder)),
    Title("歌名", SongTitleOrder),
}
