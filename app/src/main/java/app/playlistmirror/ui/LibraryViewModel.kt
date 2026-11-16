package app.playlistmirror.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.playlistmirror.data.GamePaths
import app.playlistmirror.data.LibraryRepository
import app.playlistmirror.data.LibraryState
import app.playlistmirror.data.Playlist
import app.playlistmirror.data.PlaylistEntry
import app.playlistmirror.data.Song
import app.playlistmirror.sync.BeatSaverClient
import app.playlistmirror.sync.ItemResult
import app.playlistmirror.sync.SyncItem
import app.playlistmirror.sync.SyncRecordStore
import app.playlistmirror.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 同步分頁目前的狀態。 */
sealed interface SyncUiState {
    /** 待命：顯示上次同步時間，以及剛完成的同步結果（如果有） */
    data class Idle(val lastSyncedAt: Long?, val lastResults: List<ItemResult>? = null) : SyncUiState
    /** 正在讀取兩邊、算摘要 */
    data object Checking : SyncUiState
    /** 摘要畫面：等你確認 */
    data class Review(val preview: SyncService.Preview) : SyncUiState
    /** 執行中 */
    data class Running(val done: Int, val total: Int, val label: String) : SyncUiState
    data class Error(val message: String) : SyncUiState
}

/**
 * 畫面與「歌曲庫管理員」之間的中間人。
 *
 * 為什麼需要它：手機轉向、切換畫面時，畫面會被重建，但 ViewModel 會留著，
 * 所以正在進行的讀檔、目前的狀態都不會不見。
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LibraryRepository(
        gameRoot = GamePaths.root(),
        cacheFile = File(app.filesDir, "song-cache.json"),
    )

    val state: StateFlow<LibraryState> = repository.state
    val gameRootPath: String get() = repository.gameRootPath

    /** 要用小提示條（Snackbar）告訴使用者的訊息，顯示後清掉。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /** 第一次進到主畫面時才讀取，避免每次切換畫面都重讀。 */
    fun loadIfNeeded() {
        if (!state.value.loadedOnce && !state.value.loading) reload()
    }

    fun reload() = run { repository.reload() }

    fun setSongInPlaylist(song: Song, playlist: Playlist, include: Boolean) =
        run { repository.setSongInPlaylist(song, playlist, include) }

    fun setSongPlaylists(song: Song, selectedPaths: Set<String>) =
        run("已更新「${song.title}」的歌單") { repository.setSongPlaylists(song, selectedPaths) }

    fun removeEntry(playlist: Playlist, entry: PlaylistEntry) =
        run { repository.removeEntry(playlist, entry) }

    fun deleteSong(song: Song) = run("已刪除「${song.title}」") { repository.deleteSong(song) }

    // ───────────── 同步 ─────────────

    private val syncService = SyncService(
        gameRoot = GamePaths.root(),
        recordStore = SyncRecordStore(File(app.filesDir, "sync-record.json")),
        // 拿到 BeatSaver 授權後，這裡會帶上登入的通行證
        clientFactory = { BeatSaverClient() },
    )

    private val _sync = MutableStateFlow<SyncUiState>(SyncUiState.Idle(null))
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _sync.value = SyncUiState.Idle(runCatching { syncService.lastSyncedAt() }.getOrNull())
        }
    }

    /** 按下「同步全部歌單」：讀取兩邊、算出摘要。這一步不改動任何東西。 */
    fun startSync() {
        _sync.value = SyncUiState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            _sync.value = try {
                if (!state.value.loadedOnce) repository.reload()
                SyncUiState.Review(syncService.prepare(state.value))
            } catch (e: Exception) {
                SyncUiState.Error("讀取 BeatSaver 失敗：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 在摘要畫面按「確認同步」：執行勾選的項目。 */
    fun confirmSync(preview: SyncService.Preview, selected: List<SyncItem>) {
        _sync.value = SyncUiState.Running(0, selected.size, "準備中…")
        viewModelScope.launch(Dispatchers.IO) {
            _sync.value = try {
                val results = syncService.execute(
                    preview = preview,
                    selected = selected,
                    library = state.value,
                    reload = { repository.reload(); state.value },
                    onProgress = { done, total, label -> _sync.value = SyncUiState.Running(done, total, label) },
                )
                SyncUiState.Idle(syncService.lastSyncedAt(), results)
            } catch (e: Exception) {
                SyncUiState.Error("同步失敗：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 在摘要畫面按「取消」，或關掉錯誤訊息：回到待命，什麼都不改。 */
    fun cancelSync() {
        _sync.value = SyncUiState.Idle(runCatching { syncService.lastSyncedAt() }.getOrNull())
    }

    /** 在背景執行一個動作；成功時可顯示提示，失敗時一定顯示原因。 */
    private fun run(successMessage: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                successMessage?.let { _message.value = it }
            } catch (e: Exception) {
                _message.value = "操作失敗：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
