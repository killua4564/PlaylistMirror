package app.playlistmirror.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.playlistmirror.data.LibraryState
import app.playlistmirror.data.Playlist
import app.playlistmirror.data.Song

/**
 * 歌曲頁面：顯示一首歌的資訊與目前所屬歌單，並提供兩個動作：
 * 1. 管理歌單：按下後才跳出歌單視窗，勾選這首歌要放在哪些歌單，按「儲存」才生效
 * 2. 刪除歌曲：從 Quest 移除，並從所有歌單拿掉
 */
@Composable
fun SongDetailScreen(
    state: LibraryState,
    hash: String,
    onSavePlaylists: (Song, Set<String>) -> Unit,
    onDelete: (Song) -> Unit,
    onBack: () -> Unit,
) {
    val song = state.songsByHash[hash]
    var confirmDelete by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }

    ScreenScaffold(title = song?.title ?: "歌曲", onBack = onBack) { padding ->
        if (song == null) {
            EmptyHint("找不到這首歌，可能已經被刪除。", Modifier.padding(padding))
            return@ScreenScaffold
        }
        val playlists = remember(state.playlists) { state.playlists.sortedWith(PlaylistTitleOrder) }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 歌曲資訊 ──
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall)
                song.subTitle?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                InfoLine("歌手", song.songAuthor ?: "—")
                InfoLine("製譜者", song.mapper ?: "—")
                InfoLine("時間長度", formatDuration(song.durationSeconds))
                InfoLine("下載日期", formatDate(song.downloadedAt))
                Spacer(Modifier.height(8.dp))
                DifficultyBadges(song.difficulties, long = true)
            }

            HorizontalDivider(Modifier.padding(top = 12.dp))

            // ── 所屬歌單：只顯示一行摘要，要修改請按「管理歌單」 ──
            SectionTitle("所屬歌單")
            val memberOf = playlists.filter { song.hash in it.hashes }
            Text(
                if (memberOf.isEmpty()) "不在任何歌單" else memberOf.joinToString("、") { it.title },
                color = if (memberOf.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // ── 兩個動作：管理歌單、刪除歌曲 ──
            Row(Modifier.padding(16.dp)) {
                Button(onClick = { managing = true }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("管理歌單")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("刪除歌曲")
                }
            }
        }

        if (managing) {
            ManagePlaylistsDialog(
                playlists = playlists,
                initiallySelected = playlists.filter { song.hash in it.hashes }.map { it.file.absolutePath }.toSet(),
                onSave = { selected -> onSavePlaylists(song, selected); managing = false },
                onDismiss = { managing = false },
            )
        }

        if (confirmDelete) {
            ConfirmDialog(
                title = "刪除「${song.title}」？",
                text = "會從 Quest 移除這首歌的檔案，並從所有歌單拿掉。這個動作無法復原。",
                confirmLabel = "刪除",
                onConfirm = { onDelete(song); onBack() },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

/**
 * 管理歌單的視窗。
 *
 * 勾選只是先記在視窗裡，按「儲存」才真的改檔案；按「取消」什麼都不會變。
 * 歌單很多時，可以用上方的搜尋框縮小範圍。
 *
 * @param initiallySelected 打開視窗時已勾選的歌單（用歌單檔案路徑辨識）
 * @param onSave 回傳最後勾選的歌單檔案路徑
 */
@Composable
private fun ManagePlaylistsDialog(
    playlists: List<Playlist>,
    initiallySelected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(initiallySelected) }
    var filter by remember { mutableStateOf("") }
    val shown = playlists.filter { filter.isBlank() || it.title.contains(filter.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理歌單") },
        text = {
            Column {
                if (playlists.size > 6) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("搜尋歌單") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                if (playlists.isEmpty()) {
                    Text("還沒有任何歌單。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 視窗高度有上限，歌單多時在視窗內捲動
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.file.absolutePath }) { playlist ->
                        val path = playlist.file.absolutePath
                        val checked = path in selected
                        val toggle = { selected = if (checked) selected - path else selected + path }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { toggle() },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Text(playlist.title)
                        }
                    }
                }
                Text(
                    "沒有適合的歌單？到 BeatSaver 網站新增歌單，同步後就會出現在這裡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }, enabled = selected != initiallySelected) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value)
    }
}
