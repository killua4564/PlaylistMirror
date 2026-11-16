package app.playlistmirror.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.playlistmirror.data.LibraryState
import app.playlistmirror.data.Playlist
import app.playlistmirror.data.PlaylistEntry

/**
 * 歌單分頁：所有歌單，依名稱排序（不分大小寫）。
 *
 * App 不提供新增、刪除歌單：App 沒有權限在 BeatSaver 建立或刪除歌單，
 * 在 Quest 上單獨新增或刪除的歌單無法同步，所以歌單一律在 BeatSaver 網站建立或刪除，
 * App 只負責把它們鏡像到 Quest。
 * 「尚未連結」的提示要等階段 2（同步）才會出現，因為那時才有連結的概念。
 */
@Composable
fun PlaylistListScreen(
    state: LibraryState,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val playlists = remember(state.playlists) { state.playlists.sortedWith(PlaylistTitleOrder) }

    ScreenScaffold(
        title = "歌單",
        actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 有讀不到的歌單檔時提醒使用者，不要默默略過
            if (state.brokenPlaylists.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("有 ${state.brokenPlaylists.size} 個歌單檔讀不到", color = MaterialTheme.colorScheme.error)
                        state.brokenPlaylists.forEach {
                            Text("・${it.file.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (playlists.isEmpty()) {
                EmptyHint(
                    if (state.loading) "正在讀取歌單…"
                    else "Quest 上還沒有歌單。\n請到 BeatSaver 網站建立歌單，同步後就會出現在這裡。",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(playlists, key = { it.file.absolutePath }) { playlist ->
                        ListItem(
                            modifier = Modifier.clickable { onOpenPlaylist(playlist) },
                            headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text("${playlist.entries.size} 首歌", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 歌單內容：歌曲依歌名排序（不分大小寫），每首右邊可以移出歌單。
 * 歌單裡有、但 Quest 上沒裝的歌，標示「未安裝」。
 */
@Composable
fun PlaylistDetailScreen(
    state: LibraryState,
    filePath: String,
    onRemove: (Playlist, PlaylistEntry) -> Unit,
    onOpenSong: (String) -> Unit,
    onBack: () -> Unit,
) {
    val playlist = state.playlists.firstOrNull { it.file.absolutePath == filePath }

    ScreenScaffold(title = playlist?.title ?: "歌單", onBack = onBack) { padding ->
        if (playlist == null) {
            EmptyHint("找不到這個歌單，可能已經被刪除。", Modifier.padding(padding))
            return@ScreenScaffold
        }

        // 每筆紀錄配上對應的歌曲（如果有安裝），再依顯示名稱排序
        val rows = remember(playlist, state.songsByHash) {
            playlist.entries
                .map { entry -> entry to entry.hash?.let { state.songsByHash[it] } }
                .sortedWith(compareBy(NameOrder) { (entry, song) -> song?.title ?: entry.songName ?: entry.hash ?: "" })
        }

        if (rows.isEmpty()) {
            EmptyHint("這個歌單還沒有歌。\n到「歌曲」分頁點一首歌，按「管理歌單」就能加進來。", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(rows) { (entry, song) ->
                    ListItem(
                        modifier = if (song != null) Modifier.clickable { onOpenSong(song.hash) } else Modifier,
                        headlineContent = {
                            Text(
                                song?.title ?: entry.songName ?: "（沒有歌名）",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            if (song != null) {
                                Text(
                                    listOfNotNull(song.mapper, formatDuration(song.durationSeconds)).joinToString("・"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text("未安裝在 Quest 上", color = MaterialTheme.colorScheme.secondary)
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemove(playlist, entry) }) {
                                Icon(Icons.Default.Close, contentDescription = "移出歌單")
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
