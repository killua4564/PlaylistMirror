package app.playlistmirror.sync

import app.playlistmirror.data.Playlist
import app.playlistmirror.data.PlaylistEntry
import app.playlistmirror.data.Song

/**
 * 同步的「大腦」：比對 Quest、BeatSaver、上次同步記錄三方，算出要做哪些事。
 *
 * 這裡只做判斷，不碰檔案也不連網路，所以可以用單元測試把每一種情況都驗過。
 *
 * 核心規則（對照規格書「四類落差」）：
 * | Quest 現在 | BeatSaver 現在 | 上次同步時 | 判斷                 | 動作                    |
 * |-----------|---------------|-----------|---------------------|------------------------|
 * | 沒有       | 有             | 沒有       | BeatSaver 新加的      | 下載到 Quest             |
 * | 沒有       | 有             | 有（已安裝）| 你在遊戲裡刪掉／移出了  | 從 BeatSaver 移除        |
 * | 有         | 沒有           | 沒有       | 你在 Quest 新加的     | 加到 BeatSaver           |
 * | 有         | 沒有           | 有         | 你在 BeatSaver 移除了  | 從 Quest 歌單移出（檔案保留）|
 *
 * 「Quest 有」的意思是：這首歌在 Quest 歌單裡，而且有安裝。
 * 第一次同步（沒有記錄）時，落差都列為「請你決定」。
 */
object SyncPlanner {

    /**
     * @param local Quest 上的歌單。沒有 syncURL 的歌單不參與同步。
     * @param cloud 這次從 BeatSaver 拿到的歌單，以歌單 ID 為鍵：
     *   - 值是歌單內容：正常比對
     *   - 值是 null：確認 BeatSaver 上已刪除這個歌單
     *   - 沒有這個鍵：這次沒查到（例如網路錯誤），這個歌單本次略過，不做任何判斷
     * @param installed Quest 上已安裝的歌
     * @param record 上次同步的記錄
     */
    fun plan(
        local: List<Playlist>,
        cloud: Map<String, CloudPlaylist?>,
        installed: List<Song>,
        record: SyncRecord,
    ): List<SyncItem> {
        val lookup = Lookup(installed, cloud.values.filterNotNull(), record)
        val items = mutableListOf<SyncItem>()
        val linked = local.filter { it.cloudId != null }

        for (playlist in linked) {
            val id = playlist.cloudId!!
            if (!cloud.containsKey(id)) continue // 這次沒查到，略過
            val cloudPlaylist = cloud[id]
            if (cloudPlaylist == null) {
                // BeatSaver 上已刪除 → Quest 上也刪除這個歌單（鏡像）。
                // 方向可以確定：歌單只在 BeatSaver 網站建立或刪除，遊戲和 App 都不能刪歌單。
                // 但如果沒有上次同步的記錄（例如歌單檔裡是舊的、已失效的 ID），標為「請你決定」。
                items += SyncItem(
                    SyncKind.DeleteQuestPlaylist, id, playlist.title,
                    needsDecision = record.playlists[id] == null,
                )
            } else {
                items += diffPlaylist(playlist, cloudPlaylist, record.playlists[id], lookup)
            }
        }

        // BeatSaver 上有、Quest 上沒有的歌單：在 Quest 建立，並下載還沒安裝的歌
        val localIds = linked.mapNotNull { it.cloudId }.toSet()
        for ((id, cloudPlaylist) in cloud) {
            if (cloudPlaylist == null || id in localIds) continue
            items += SyncItem(SyncKind.CreateQuestPlaylist, id, cloudPlaylist.title)
            for (song in cloudPlaylist.songs) {
                val installedSong = lookup.installed(song.hash, song.key)
                items += when {
                    installedSong == null -> songItem(SyncKind.Download, cloudPlaylist, song)
                    installedSong.hash != song.hash ->
                        songItem(SyncKind.Update, cloudPlaylist, song).copy(oldHash = installedSong.hash)
                    else -> continue
                }
            }
        }
        return items
    }

    /** 比對同一個歌單在兩邊的差異。 */
    private fun diffPlaylist(
        local: Playlist,
        cloud: CloudPlaylist,
        record: PlaylistRecord?,
        lookup: Lookup,
    ): List<SyncItem> {
        val firstSync = record == null
        val localById = local.entries.mapNotNull { e -> lookup.identityOf(e)?.let { it to e } }.toMap()
        val cloudById = cloud.songs.associateBy { lookup.identityOf(it.key, it.hash) }
        val ids = localById.keys + cloudById.keys + record?.songs?.keys.orEmpty()

        val items = mutableListOf<SyncItem>()
        for (id in ids) {
            val entry = localById[id]
            val cloudSong = cloudById[id]
            val previous = record?.songs?.get(id)
            val key = cloudSong?.key ?: entry?.key ?: previous?.key
            val installedSong = lookup.installed(entry?.hash ?: cloudSong?.hash ?: previous?.hash, key)
            val inQuest = entry != null && installedSong != null
            val name = cloudSong?.songName ?: entry?.songName ?: installedSong?.title

            fun item(kind: SyncKind, hash: String?, decide: Boolean = false) = SyncItem(
                kind = kind, playlistId = cloud.id, playlistTitle = cloud.title, identity = id,
                key = key, hash = hash, songName = name, needsDecision = decide,
            )

            when {
                // 兩邊都有：檢查是否有新版本
                cloudSong != null && inQuest -> {
                    if (installedSong!!.hash != cloudSong.hash) {
                        items += item(SyncKind.Update, cloudSong.hash).copy(oldHash = installedSong.hash)
                    }
                }

                // BeatSaver 有、Quest 沒有
                cloudSong != null -> when {
                    // 上次同步時在 Quest 上（已安裝）→ 你刪掉或移出了 → 從 BeatSaver 移除
                    previous != null && previous.installed -> items += item(SyncKind.RemoveFromBeatSaver, cloudSong.hash)
                    // 歌已安裝、只是不在 Quest 歌單裡 → 加進 Quest 歌單
                    installedSong != null -> items += item(SyncKind.AddToQuestPlaylist, installedSong.hash, firstSync)
                    // 沒安裝 → 下載
                    else -> items += item(SyncKind.Download, cloudSong.hash, firstSync)
                }

                // Quest 有、BeatSaver 沒有
                inQuest -> when {
                    previous != null -> items += item(SyncKind.RemoveFromQuestPlaylist, entry!!.hash)
                    else -> items += item(SyncKind.AddToBeatSaver, installedSong!!.hash, firstSync)
                }

                // Quest 歌單裡有、但沒安裝，BeatSaver 也沒有 → 清掉這筆找不到的紀錄
                entry != null -> items += item(SyncKind.RemoveFromQuestPlaylist, entry.hash)

                // 只剩上次記錄、兩邊都沒有了 → 不用做事
                else -> Unit
            }
        }
        return items
    }

    private fun songItem(kind: SyncKind, playlist: CloudPlaylist, song: CloudSong) = SyncItem(
        kind = kind, playlistId = playlist.id, playlistTitle = playlist.title,
        identity = song.identity, key = song.key, hash = song.hash, songName = song.songName,
    )

    /**
     * 同步完成後的新記錄。
     *
     * 兩邊都有的歌記為「已同步」；沒有執行的項目（你取消勾選、或需要授權還不能寫回）
     * 保留原本的記錄，下次同步才會再次出現，不會被誤判。
     *
     * @param pending 這次沒有執行的項目
     */
    fun nextRecord(
        old: SyncRecord,
        local: List<Playlist>,
        cloud: Map<String, CloudPlaylist?>,
        installed: List<Song>,
        pending: List<SyncItem>,
        now: Long,
    ): SyncRecord {
        val lookup = Lookup(installed, cloud.values.filterNotNull(), old)
        val result = old.playlists.toMutableMap()

        for ((id, cloudPlaylist) in cloud) {
            if (cloudPlaylist == null) {
                result.remove(id) // BeatSaver 上已刪除，不再記錄
                continue
            }
            val localPlaylist = local.firstOrNull { it.cloudId == id } ?: continue
            val localById = localPlaylist.entries.mapNotNull { e -> lookup.identityOf(e)?.let { it to e } }.toMap()
            val songs = mutableMapOf<String, SongRecord>()
            for (song in cloudPlaylist.songs) {
                val identity = lookup.identityOf(song.key, song.hash)
                val entry = localById[identity] ?: continue
                val installedSong = lookup.installed(entry.hash ?: song.hash, song.key ?: entry.key)
                songs[identity] = SongRecord(identity, song.key ?: entry.key, song.hash, installedSong != null)
            }
            // 沒執行的項目：沿用舊記錄（舊記錄沒有就不記）
            val previous = old.playlists[id]?.songs.orEmpty()
            for (item in pending.filter { it.playlistId == id && it.identity != null }) {
                val identity = item.identity!!
                val before = previous[identity]
                if (before != null) songs[identity] = before else songs.remove(identity)
            }
            result[id] = PlaylistRecord(id, songs)
        }
        return SyncRecord(result, now)
    }

    /**
     * 查詢小幫手：用指紋或短 ID 找已安裝的歌，並把「沒有短 ID 的紀錄」對到正確的身分。
     */
    private class Lookup(installed: List<Song>, cloud: List<CloudPlaylist>, record: SyncRecord) {
        private val byHash = installed.associateBy { it.hash }
        private val byKey = installed.filter { it.key != null }.groupBy { it.key!! }
            .mapValues { (_, songs) -> songs.maxBy { it.downloadedAt } }

        /** 指紋 → 短 ID 的對照表（從已安裝的歌、BeatSaver 歌單、上次記錄收集） */
        private val keyOfHash: Map<String, String> = buildMap {
            record.playlists.values.flatMap { it.songs.values }.forEach { r -> r.key?.let { put(r.hash, it) } }
            installed.forEach { s -> s.key?.let { put(s.hash, it) } }
            cloud.flatMap { it.songs }.forEach { s -> s.key?.let { put(s.hash.lowercase(), it.lowercase()) } }
        }

        fun identityOf(key: String?, hash: String?): String =
            songIdentity(key ?: hash?.let { keyOfHash[it.lowercase()] }, hash)!!

        fun identityOf(entry: PlaylistEntry): String? =
            if (entry.hash == null && entry.key == null) null else identityOf(entry.key, entry.hash)

        fun installed(hash: String?, key: String?): Song? =
            hash?.let { byHash[it.lowercase()] } ?: key?.let { byKey[it.lowercase()] }
    }
}
