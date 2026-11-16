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
import app.playlistmirror.BuildConfig
import app.playlistmirror.sync.BeatSaverAuth
import app.playlistmirror.sync.BeatSaverClient
import app.playlistmirror.sync.BeatSaverSession
import app.playlistmirror.sync.ItemResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import app.playlistmirror.sync.SyncItem
import app.playlistmirror.sync.SyncRecordStore
import app.playlistmirror.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** BeatSaver 登入狀態。 */
sealed interface AuthUiState {
    /** 這個版本沒有帶 BeatSaver 憑證（secrets.properties 不存在），無法登入 */
    data object NotConfigured : AuthUiState
    data object LoggedOut : AuthUiState
    /** 正在向 BeatSaver 申請代碼 */
    data object Requesting : AuthUiState
    /** 等你在手機上輸入代碼 */
    data class Waiting(val code: BeatSaverAuth.DeviceCode, val expiresAt: Long) : AuthUiState
    data class LoggedIn(val name: String) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

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

    // ───────────── BeatSaver 登入 ─────────────

    private val session = BeatSaverSession(
        app,
        BeatSaverAuth(BuildConfig.BEATSAVER_CLIENT_ID, BuildConfig.BEATSAVER_CLIENT_SECRET),
    )

    private val _auth = MutableStateFlow(currentAuthState())
    val auth: StateFlow<AuthUiState> = _auth.asStateFlow()

    private var loginJob: Job? = null

    private fun currentAuthState(): AuthUiState = when {
        !session.auth.configured -> AuthUiState.NotConfigured
        else -> session.account?.let { AuthUiState.LoggedIn(it.name) } ?: AuthUiState.LoggedOut
    }

    /**
     * 開始用裝置代碼登入：申請代碼 → 顯示給你 → 每隔幾秒問一次「登入了沒」。
     * 你在手機上完成登入後，這裡會自動變成「已登入」。
     */
    fun startLogin() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _auth.value = AuthUiState.Requesting
                val code = session.auth.requestDeviceCode()
                val expiresAt = System.currentTimeMillis() + code.expiresInSeconds * 1000L
                _auth.value = AuthUiState.Waiting(code, expiresAt)
                var interval = code.intervalSeconds * 1000L
                while (isActive && System.currentTimeMillis() < expiresAt) {
                    delay(interval)
                    when (val result = session.auth.poll(code.deviceCode)) {
                        is BeatSaverAuth.PollResult.Success -> {
                            val identity = session.auth.identity(result.tokens.accessToken)
                            session.save(result.tokens, identity)
                            _auth.value = AuthUiState.LoggedIn(identity.name)
                            _message.value = "已登入 BeatSaver：${identity.name}"
                            return@launch
                        }
                        BeatSaverAuth.PollResult.Pending -> Unit
                        BeatSaverAuth.PollResult.SlowDown -> interval += 5_000
                        is BeatSaverAuth.PollResult.Failed -> {
                            _auth.value = AuthUiState.Error(result.reason)
                            return@launch
                        }
                    }
                }
                if (isActive) _auth.value = AuthUiState.Error("代碼已過期，請重新登入")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _auth.value = AuthUiState.Error("登入失敗：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 取消登入，或關掉登入錯誤訊息。 */
    fun cancelLogin() {
        loginJob?.cancel()
        _auth.value = currentAuthState()
    }

    fun logout() {
        loginJob?.cancel()
        session.logout()
        _auth.value = currentAuthState()
        _message.value = "已登出 BeatSaver"
    }

    // ───────────── 同步 ─────────────

    private val syncService = SyncService(
        gameRoot = GamePaths.root(),
        recordStore = SyncRecordStore(File(app.filesDir, "sync-record.json")),
        // 有登入就帶上通行證（快過期會自動續用），才能讀私人歌單、寫回 BeatSaver
        clientFactory = { BeatSaverClient(accessToken = session.validAccessToken()) },
        currentUserId = { session.account?.userId },
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
            // 續用失敗時可能已被自動登出，更新登入狀態
            if (loginJob?.isActive != true) _auth.value = currentAuthState()
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
