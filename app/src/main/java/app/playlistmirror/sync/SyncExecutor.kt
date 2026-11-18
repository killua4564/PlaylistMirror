package app.playlistmirror.sync

import app.playlistmirror.data.GamePaths
import app.playlistmirror.data.Playlist
import app.playlistmirror.data.PlaylistEntry
import app.playlistmirror.data.PlaylistLink
import app.playlistmirror.data.PlaylistStore
import app.playlistmirror.data.Song
import app.playlistmirror.data.SongHasher
import app.playlistmirror.data.SongInfoParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** 一個項目執行後的結果。 */
data class ItemResult(val item: SyncItem, val status: Status, val message: String? = null) {
    enum class Status { Done, Skipped, Failed }
}

/**
 * 同步的「手」：照摘要裡勾選的項目，真的去改 Quest 上的檔案。
 *
 * 執行順序：先建立歌單 → 刪除歌單 → 移出 → 加入 → 下載 → 更新。
 * 每個項目各自成功或失敗，一個失敗不會讓其他項目停下來；失敗的項目下次同步會再出現。
 *
 * @param installed 執行前 Quest 上已安裝的歌（更新時用來找舊版資料夾）
 */
class SyncExecutor(
    gameRoot: File,
    private val client: BeatSaverClient,
    private val cloud: Map<String, CloudPlaylist?>,
    private val installed: List<Song>,
) {
    private val songsDir = File(gameRoot, GamePaths.SONG_CORE)
    private val store = PlaylistStore(File(gameRoot, GamePaths.PLAYLISTS))

    /** 依序執行；onProgress 回報「做完幾個、共幾個、正在做什麼」。 */
    fun execute(items: List<SyncItem>, onProgress: (Int, Int, String) -> Unit): List<ItemResult> {
        val ordered = items.sortedBy { order.indexOf(it.kind) }
        return ordered.mapIndexed { index, item ->
            onProgress(index, ordered.size, describe(item))
            val result = try {
                run(item)
            } catch (e: Exception) {
                ItemResult(item, ItemResult.Status.Failed, e.message ?: e.javaClass.simpleName)
            }
            onProgress(index + 1, ordered.size, describe(item))
            result
        }
    }

    private val order = listOf(
        SyncKind.CreateQuestPlaylist, SyncKind.DeleteQuestPlaylist,
        SyncKind.RenameQuestPlaylist, SyncKind.RemoveFromQuestPlaylist,
        SyncKind.AddToQuestPlaylist, SyncKind.Download, SyncKind.Update,
        SyncKind.AddToBeatSaver, SyncKind.RemoveFromBeatSaver,
    )

    private fun run(item: SyncItem): ItemResult {
        when (item.kind) {
            SyncKind.CreateQuestPlaylist -> {
                val playlist = cloud[item.playlistId] ?: throw IOException("找不到 BeatSaver 歌單內容")
                store.createFromRaw(playlist.title, ensureSyncUrl(playlist))
            }
            SyncKind.DeleteQuestPlaylist -> {
                // 只刪歌單檔；歌單裡的歌不刪，因為其他歌單可能也有這些歌
                store.delete(playlistFor(item.playlistId))
            }
            SyncKind.RenameQuestPlaylist -> {
                // 只改歌單裡的名稱欄位；檔名維持原樣（遊戲認的是裡面的名稱，不是檔名）
                store.replaceFields(playlistFor(item.playlistId), mapOf("playlistTitle" to JsonPrimitive(item.playlistTitle)))
            }
            SyncKind.RemoveFromQuestPlaylist -> {
                val playlist = playlistFor(item.playlistId)
                store.save(playlist, playlist.entries.filterNot { matches(it, item.key, item.hash) })
            }
            SyncKind.AddToQuestPlaylist -> addToPlaylist(item, item.hash!!)
            SyncKind.Download -> {
                val warning = installSong(item.hash!!)
                addToPlaylist(item, item.hash)
                if (warning != null) return ItemResult(item, ItemResult.Status.Done, warning)
            }
            SyncKind.Update -> {
                val warning = installSong(item.hash!!)
                // 所有歌單裡的舊版都改指向新版
                for (playlist in store.loadAll().first) {
                    if (playlist.entries.none { it.hash == item.oldHash }) continue
                    store.save(playlist, playlist.entries.map { e ->
                        if (e.hash == item.oldHash) withHash(e, item.hash) else e
                    })
                }
                // 刪除舊版資料夾
                installed.firstOrNull { it.hash == item.oldHash }?.folder?.let { old ->
                    if (old.exists() && !old.deleteRecursively()) throw IOException("新版已安裝，但舊版資料夾刪不掉：${old.name}")
                }
                if (warning != null) return ItemResult(item, ItemResult.Status.Done, warning)
            }
            SyncKind.AddToBeatSaver, SyncKind.RemoveFromBeatSaver -> {
                if (!client.canWrite) {
                    return ItemResult(item, ItemResult.Status.Skipped, "需要登入 BeatSaver 才能寫回")
                }
                val add = item.kind == SyncKind.AddToBeatSaver
                // 優先用短 ID；沒有短 ID 才用指紋
                client.batch(
                    playlistId = item.playlistId,
                    keys = listOfNotNull(item.key),
                    hashes = if (item.key == null) listOfNotNull(item.hash) else emptyList(),
                    add = add,
                )
                if (!add) {
                    // 你在遊戲裡刪掉的歌，Quest 歌單可能還留著找不到的紀錄，一併清掉
                    val playlist = store.loadAll().first.firstOrNull { it.cloudId == item.playlistId }
                    if (playlist != null && playlist.entries.any { matches(it, item.key, item.hash) }) {
                        store.save(playlist, playlist.entries.filterNot { matches(it, item.key, item.hash) })
                    }
                }
            }
        }
        return ItemResult(item, ItemResult.Status.Done)
    }

    // ───────────── 安裝歌曲 ─────────────

    /**
     * 從 BeatSaver 下載並安裝一首歌。
     * 先解壓到暫存資料夾，檢查無誤再改成正式名稱，避免留下只裝一半的歌。
     *
     * @return 指紋和 BeatSaver 不一致時的警告文字（歌仍會安裝）；一致時回傳 null
     */
    private fun installSong(hash: String): String? {
        val info = client.mapsByHashes(listOf(hash))[hash.lowercase()]
            ?: throw IOException("BeatSaver 上找不到這首歌")
        val zip = client.download(info.downloadUrl)

        songsDir.mkdirs()
        val temp = File(songsDir, ".pm-download-$hash")
        if (temp.exists()) temp.deleteRecursively()
        temp.mkdirs()
        try {
            unzip(zip, temp)
            val infoFile = listOf("Info.dat", "info.dat").map { File(temp, it) }.firstOrNull { it.isFile }
                ?: throw IOException("下載的檔案裡沒有 Info.dat")
            val computed = SongHasher.hash(temp, infoFile, SongInfoParser.parse(infoFile.readText()).filesToHash)

            val target = uniqueFolder("${info.key} (${info.songName} - ${info.mapper ?: "Unknown"})")
            if (!temp.renameTo(target)) throw IOException("無法建立歌曲資料夾")
            return if (computed != info.hash) "指紋和 BeatSaver 不一致（App 算出 $computed），請回報" else null
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    /** 解壓縮，並擋掉「路徑跳出資料夾」的惡意壓縮檔。 */
    private fun unzip(bytes: ByteArray, target: File) {
        val root = target.canonicalPath + File.separator
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("壓縮檔內容不安全：${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    private fun uniqueFolder(name: String): File {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        var folder = File(songsDir, safe)
        var n = 2
        while (folder.exists()) {
            folder = File(songsDir, "$safe ($n)")
            n++
        }
        return folder
    }

    // ───────────── 歌單小工具 ─────────────

    /** 每次都重新讀檔，確保拿到最新內容（同一個歌單可能被前面的項目改過）。 */
    private fun playlistFor(cloudId: String): Playlist =
        store.loadAll().first.firstOrNull { it.cloudId == cloudId }
            ?: throw IOException("Quest 上找不到這個歌單")

    private fun addToPlaylist(item: SyncItem, hash: String) {
        val playlist = playlistFor(item.playlistId)
        val existing = playlist.entries.indexOfFirst { matches(it, item.key, hash) }
        val newEntries = if (existing >= 0) {
            // 已經有同一首歌的紀錄（例如舊版指紋），改成正確的指紋
            playlist.entries.mapIndexed { i, e -> if (i == existing) withHash(e, hash) else e }
        } else {
            playlist.entries + PlaylistEntry(hash, item.songName, buildJsonObject {
                item.key?.let { put("key", it) }
                put("hash", hash)
                item.songName?.let { put("songName", it) }
            }, item.key)
        }
        store.save(playlist, newEntries)
    }

    private fun matches(entry: PlaylistEntry, key: String?, hash: String?): Boolean =
        (hash != null && entry.hash == hash.lowercase()) || (key != null && entry.key == key.lowercase())

    private fun withHash(entry: PlaylistEntry, hash: String): PlaylistEntry =
        entry.copy(hash = hash, raw = JsonObject(entry.raw.toMutableMap().apply { put("hash", JsonPrimitive(hash)) }))

    /** BeatSaver 下載的歌單內容通常已有 syncURL；保險起見確認一次。 */
    private fun ensureSyncUrl(playlist: CloudPlaylist): JsonObject {
        val customData = (playlist.raw["customData"] as? JsonObject).orEmpty().toMutableMap()
        customData["syncURL"] = JsonPrimitive(PlaylistLink.syncUrlFor(playlist.id))
        return JsonObject(playlist.raw.toMutableMap().apply { put("customData", JsonObject(customData)) })
    }

    companion object {
        /** 摘要與進度用的一句話說明。 */
        fun describe(item: SyncItem): String {
            val song = item.songName ?: item.key ?: "（未知歌曲）"
            return when (item.kind) {
                SyncKind.Download -> "下載「$song」"
                SyncKind.AddToQuestPlaylist -> "把「$song」加進歌單"
                SyncKind.RemoveFromQuestPlaylist -> "把「$song」移出歌單"
                SyncKind.Update -> "更新「$song」到新版"
                SyncKind.CreateQuestPlaylist -> "建立歌單「${item.playlistTitle}」"
                SyncKind.DeleteQuestPlaylist -> "刪除歌單「${item.playlistTitle}」（BeatSaver 上已刪除；歌曲檔案保留）"
                SyncKind.RenameQuestPlaylist -> "把歌單「${item.oldTitle}」改名為「${item.playlistTitle}」"
                SyncKind.AddToBeatSaver -> "把「$song」加到 BeatSaver"
                SyncKind.RemoveFromBeatSaver -> "把「$song」從 BeatSaver 移除"
            }
        }
    }
}
