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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

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
