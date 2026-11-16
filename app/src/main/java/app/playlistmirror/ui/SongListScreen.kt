@file:OptIn(ExperimentalMaterial3Api::class)

package app.playlistmirror.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.playlistmirror.data.LibraryState
import app.playlistmirror.data.Song

/**
 * 歌曲分頁：所有已安裝的歌。
 * 上方有搜尋框、「全部／不在任何歌單」兩個篩選、右上角可以切換排序。
 */
@Composable
fun SongListScreen(
    state: LibraryState,
    onOpenSong: (Song) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // rememberSaveable：切到別的分頁再回來，搜尋字和篩選還在
    var query by rememberSaveable { mutableStateOf("") }
    var onlyUnlisted by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SongSort.NewestFirst) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val unlistedCount = remember(state) { state.songs.count { it.hash !in state.listedHashes } }

    // 依照搜尋、篩選、排序算出要顯示的歌。只有條件改變時才重算。
    val visible = remember(state, query, onlyUnlisted, sort) {
        state.songs.asSequence()
            .filter { !onlyUnlisted || it.hash !in state.listedHashes }
            .filter { song ->
                query.isBlank() ||
                    song.title.contains(query.trim(), ignoreCase = true) ||
                    song.subTitle?.contains(query.trim(), ignoreCase = true) == true
            }
            .sortedWith(sort.comparator)
            .toList()
    }

    ScreenScaffold(
        title = "歌曲",
        actions = {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                SongSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = {
                            if (option == sort) Icon(Icons.Default.Check, contentDescription = null)
                        },
                        onClick = { sort = option; sortMenuOpen = false },
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "設定")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("用歌名搜尋") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                FilterChip(
                    selected = !onlyUnlisted,
                    onClick = { onlyUnlisted = false },
                    label = { Text("全部 ${state.songs.size}") },
                )
                FilterChip(
                    selected = onlyUnlisted,
                    onClick = { onlyUnlisted = true },
                    label = { Text("不在任何歌單 $unlistedCount") },
                )
            }
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            when {
                state.loading && state.songs.isEmpty() -> EmptyHint("正在讀取歌曲，第一次會比較久…")
                state.songs.isEmpty() -> EmptyHint("沒有找到任何歌曲。\n請確認遊戲資料夾位置和檔案存取權限（右上角設定）。")
                visible.isEmpty() -> EmptyHint(if (onlyUnlisted) "所有歌曲都已放進歌單。" else "沒有符合的歌曲。")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.hash }) { song ->
                        SongRow(song, onClick = { onOpenSong(song) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

/** 清單裡的一列：歌名、製譜者・時間長度、難度標籤。 */
@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { SongCover(song) },
        headlineContent = {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(song.mapper, formatDuration(song.durationSeconds)).joinToString("・"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (song.difficulties.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp)) { DifficultyBadges(song.difficulties) }
                }
            }
        },
    )
}
