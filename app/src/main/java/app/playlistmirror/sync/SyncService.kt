package app.playlistmirror.sync

import app.playlistmirror.data.LibraryState
import app.playlistmirror.data.Playlist
import java.io.File

/**
 * 一次同步的完整流程：
 * 1. prepare：讀取 BeatSaver 上的歌單，和 Quest、上次記錄比對，算出摘要
 * 2. 使用者在摘要畫面確認（勾選要做的項目）
 * 3. execute：執行勾選的項目，重新讀取 Quest，更新同步記錄
 *
 * @param clientFactory 建立 BeatSaver 連線工具（拿到授權後會帶上通行證）
 */
class SyncService(
    private val gameRoot: File,
    private val recordStore: SyncRecordStore,
    private val clientFactory: () -> BeatSaverClient,
) {

    /** 摘要的內容：要做的事、讀到的 BeatSaver 歌單、以及讀不到的歌單。 */
    data class Preview(
        val items: List<SyncItem>,
        val cloud: Map<String, CloudPlaylist?>,
        /** 沒登入時看不到的歌單（可能是私人歌單），本次略過 */
        val notVisible: List<Playlist>,
        /** 因網路等原因讀取失敗的歌單，本次略過 */
        val failed: List<Pair<Playlist, String>>,
        val record: SyncRecord,
    )

    fun lastSyncedAt(): Long? = recordStore.load().syncedAt

    /** 步驟 1：讀取兩邊並算出摘要。這一步不會改動任何東西。 */
    fun prepare(library: LibraryState): Preview {
        val client = clientFactory()
        val record = recordStore.load()
        val linked = library.playlists.filter { it.cloudId != null }

        val cloud = mutableMapOf<String, CloudPlaylist?>()
        val notVisible = mutableListOf<Playlist>()
        val failed = mutableListOf<Pair<Playlist, String>>()
        for (playlist in linked) {
            val id = playlist.cloudId!!
            try {
                when (val result = client.fetchPlaylist(id)) {
                    is BeatSaverClient.PlaylistResult.Found -> cloud[id] = result.playlist
                    BeatSaverClient.PlaylistResult.Deleted -> cloud[id] = null
                    BeatSaverClient.PlaylistResult.NotVisible -> notVisible += playlist
                }
            } catch (e: Exception) {
                failed += playlist to (e.message ?: "讀取失敗")
            }
        }

        // 找出 BeatSaver 上新建、Quest 還沒有的歌單：先從已讀到的歌單找出擁有者，再列出他的歌單
        val ownerId = cloud.keys.firstNotNullOfOrNull { runCatching { client.fetchOwnerId(it) }.getOrNull() }
        if (ownerId != null) {
            val known = linked.mapNotNull { it.cloudId }.toSet()
            val others = runCatching { client.fetchUserPlaylistIds(ownerId) }.getOrDefault(emptyList())
            for (id in others.filter { it !in known }) {
                val result = runCatching { client.fetchPlaylist(id) }.getOrNull()
                if (result is BeatSaverClient.PlaylistResult.Found) cloud[id] = result.playlist
            }
        }

        val items = SyncPlanner.plan(library.playlists, cloud, library.songs, record)
        return Preview(items, cloud, notVisible, failed, record)
    }

    /**
     * 步驟 3：執行勾選的項目，然後更新同步記錄。
     *
     * @param selected 使用者勾選的項目（沒勾的會保留到下次）
     * @param reload 執行後重新讀取 Quest 上的歌曲和歌單
     */
    suspend fun execute(
        preview: Preview,
        selected: List<SyncItem>,
        library: LibraryState,
        reload: suspend () -> LibraryState,
        onProgress: (Int, Int, String) -> Unit,
    ): List<ItemResult> {
        val executor = SyncExecutor(gameRoot, clientFactory(), preview.cloud, library.songs)
        val results = executor.execute(selected, onProgress)

        val done = results.filter { it.status == ItemResult.Status.Done }.map { it.item }.toSet()
        val pending = preview.items.filter { it !in done }
        val after = reload()
        val record = SyncPlanner.nextRecord(
            old = preview.record,
            local = after.playlists,
            cloud = preview.cloud,
            installed = after.songs,
            pending = pending,
            now = System.currentTimeMillis(),
        )
        recordStore.save(record)
        return results
    }
}
