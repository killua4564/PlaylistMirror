package app.playlistmirror.data

/**
 * Quest 歌單與 BeatSaver 歌單的對應方式。
 *
 * 從 BeatSaver 下載的歌單檔，customData 裡有一個 syncURL，例如：
 *   https://api.beatsaver.com/playlists/id/12345/download
 * 最後的 12345 就是 BeatSaver 上的歌單 ID。App 用它判斷「這個 Quest 歌單對應哪個 BeatSaver 歌單」，
 * 不需要手動連結，也不另外記錄。
 */
object PlaylistLink {

    private val idPattern = Regex("""/playlists/id/(\d+)(?:/|$)""")

    /** 從 syncURL 取出歌單 ID；不是 BeatSaver 的歌單網址就回傳 null。 */
    fun idFromSyncUrl(url: String?): String? {
        if (url.isNullOrBlank() || !url.contains("beatsaver.com")) return null
        return idPattern.find(url)?.groupValues?.get(1)
    }

    /** 由歌單 ID 組出 syncURL（建立新歌單檔時寫入，格式和 BeatSaver 下載的一樣）。 */
    fun syncUrlFor(id: String): String = "https://api.beatsaver.com/playlists/id/$id/download"
}
