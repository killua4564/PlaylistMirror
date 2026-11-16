package app.playlistmirror.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App 目前看到的「歌曲庫」狀態。畫面只負責把這份狀態畫出來。
 */
data class LibraryState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val brokenSongs: List<BrokenFile> = emptyList(),
    val brokenPlaylists: List<BrokenFile> = emptyList(),
) {
    /** 所有歌單裡出現過的指紋。用來找出「不在任何歌單」的歌。 */
    val listedHashes: Set<String> = playlists.flatMap { it.hashes }.toSet()

    /** 用指紋查歌曲。 */
    val songsByHash: Map<String, Song> = songs.associateBy { it.hash }
}

/**
 * 歌曲庫的「管理員」：負責讀檔、改檔，並把最新狀態通知給畫面。
 *
 * 比喻：畫面是櫃台，管理員在倉庫裡做事。櫃台只要說「把這首歌加進那個歌單」，
 * 管理員就去改檔案，改完更新公告欄（state），櫃台看到公告就重畫。
 *
 * @param gameRoot 遊戲資料的根目錄（測試時可以換成假資料夾）
 * @param cacheFile 指紋備忘錄的存放位置
 */
class LibraryRepository(
    private val gameRoot: File,
    cacheFile: File,
) {
    private val songFolders = listOf(GamePaths.SONG_CORE, GamePaths.SONG_LOADER).map { File(gameRoot, it) }
    private val playlistStore = PlaylistStore(File(gameRoot, GamePaths.PLAYLISTS))
    private val cache = SongCache(cacheFile)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** 一次只做一件事，避免兩個動作同時改同一個檔案。 */
    private val mutex = Mutex()

    val gameRootPath: String get() = gameRoot.absolutePath

    /** 重新掃描所有歌曲和歌單。在背景執行，不會讓畫面卡住。 */
    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _state.update { it.copy(loading = true) }
            try {
                cache.load()
                val (songs, brokenSongs) = scanSongs()
                cache.save(songs.map { it.folder.absolutePath }.toSet())
                val (playlists, brokenPlaylists) = playlistStore.loadAll()
                _state.value = LibraryState(
                    loading = false,
                    loadedOnce = true,
                    songs = songs,
                    playlists = playlists,
                    brokenSongs = brokenSongs,
                    brokenPlaylists = brokenPlaylists,
                )
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** 掃描所有歌曲資料夾，每個子資料夾就是一首歌。 */
    private fun scanSongs(): Pair<List<Song>, List<BrokenFile>> {
        val songs = mutableListOf<Song>()
        val broken = mutableListOf<BrokenFile>()
        val seen = mutableSetOf<String>()

        for (root in songFolders) {
            val folders = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
            for (folder in folders) {
                try {
                    val song = readSong(folder) ?: continue
                    // 同一首歌如果兩個資料夾都有，只算一次
                    if (seen.add(song.hash)) songs += song
                } catch (e: Exception) {
                    broken += BrokenFile(folder, e.message ?: "無法讀取")
                }
            }
        }
        return songs to broken
    }

    /** 讀一首歌。沒有 Info.dat 的資料夾不是歌曲，回傳 null。 */
    private fun readSong(folder: File): Song? {
        val infoFile = listOf("Info.dat", "info.dat").map { File(folder, it) }.firstOrNull { it.isFile }
            ?: return null
        val info = SongInfoParser.parse(infoFile.readText())

        val path = folder.absolutePath
        val stamp = "${infoFile.lastModified()}:${infoFile.length()}"
        val cached = cache.get(path, stamp)

        val hash: String
        val duration: Int?
        if (cached != null) {
            hash = cached.hash
            duration = cached.durationSeconds
        } else {
            hash = SongHasher.hash(folder, infoFile, info.filesToHash)
            duration = info.durationSeconds
                ?: info.audioFileName?.let { AudioDuration.readSeconds(File(folder, it)) }
            cache.put(path, SongCache.Entry(stamp, hash, duration))
        }

        return Song(
            hash = hash,
            folder = folder,
            title = info.title,
            subTitle = info.subTitle,
            songAuthor = info.songAuthor,
            mapper = info.mapper,
            difficulties = info.difficulties,
            durationSeconds = duration,
            downloadedAt = folder.lastModified(),
            key = keyFromFolderName(folder.name),
        )
    }

    /**
     * 從 BeatSaver 下載的歌，資料夾名稱通常是「短 ID (歌名 - 製譜者)」，例如「3b2c (Song - Mapper)」。
     * 抓出開頭的短 ID；格式不符就回傳 null。
     */
    private fun keyFromFolderName(name: String): String? =
        Regex("""^([0-9a-fA-F]{1,8}) \(""").find(name)?.groupValues?.get(1)?.lowercase()

    // ───────────── 以下是會改動檔案的操作 ─────────────

    /** 把歌加進歌單（include = true）或從歌單移出（include = false）。 */
    suspend fun setSongInPlaylist(song: Song, playlist: Playlist, include: Boolean) = edit {
        applyMembership(song, currentPlaylist(playlist), include)
    }

    /**
     * 「管理歌單」視窗按下儲存時用：一次設定這首歌要在哪些歌單裡。
     * 只改有變動的歌單，沒變的歌單檔案不會被重寫。
     *
     * @param selectedPaths 要包含這首歌的歌單（用歌單檔案路徑辨識）
     */
    suspend fun setSongPlaylists(song: Song, selectedPaths: Set<String>) = edit {
        for (playlist in _state.value.playlists) {
            applyMembership(song, playlist, playlist.file.absolutePath in selectedPaths)
        }
    }

    private fun applyMembership(song: Song, playlist: Playlist, include: Boolean) {
        val already = song.hash in playlist.hashes
        if (include == already) return
        val newEntries = if (include) {
            playlist.entries + playlistStore.newEntry(song)
        } else {
            playlist.entries.filterNot { it.hash == song.hash }
        }
        replacePlaylist(playlistStore.save(playlist, newEntries))
    }

    /** 從歌單移除一筆紀錄（用在歌單頁面；也能移除「未安裝」的歌）。 */
    suspend fun removeEntry(playlist: Playlist, entry: PlaylistEntry) = edit {
        val current = currentPlaylist(playlist)
        replacePlaylist(playlistStore.save(current, current.entries.filterNot { it == entry }))
    }

    /**
     * 刪除一首歌：先從所有歌單拿掉，再刪除歌曲資料夾。
     * 順序很重要：如果反過來、刪到一半失敗，歌單會留下找不到的歌。
     */
    suspend fun deleteSong(song: Song) = edit {
        for (playlist in _state.value.playlists.filter { song.hash in it.hashes }) {
            replacePlaylist(playlistStore.save(playlist, playlist.entries.filterNot { it.hash == song.hash }))
        }
        if (song.folder.exists() && !song.folder.deleteRecursively()) {
            throw IllegalStateException("無法刪除歌曲資料夾：${song.folder.name}")
        }
        _state.update { s -> s.copy(songs = s.songs.filterNot { it.hash == song.hash }) }
    }

    /** 所有改檔動作的共同包裝：在背景執行、一次一件。 */
    private suspend fun edit(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    /** 拿狀態裡最新版本的歌單（畫面手上的可能是舊的）。 */
    private fun currentPlaylist(playlist: Playlist): Playlist =
        _state.value.playlists.firstOrNull { it.file == playlist.file }
            ?: throw IllegalStateException("找不到歌單：${playlist.title}")

    private fun replacePlaylist(updated: Playlist) {
        _state.update { s -> s.copy(playlists = s.playlists.map { if (it.file == updated.file) updated else it }) }
    }
}
