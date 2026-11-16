package app.playlistmirror.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.playlistmirror.BuildConfig
import app.playlistmirror.data.LibraryState

/**
 * 同步分頁（佔位）。真正的同步在階段 2、3 製作，需要 BeatSaver 的授權。
 * 先放一顆停用的按鈕，讓版面跟規格一致。
 */
@Composable
fun SyncScreen(onOpenSettings: () -> Unit) {
    ScreenScaffold(
        title = "同步",
        actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("上次同步：尚未同步", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {}, enabled = false) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text("  同步全部歌單")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "同步功能還在製作中，需要先取得 BeatSaver 的授權。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 設定頁：遊戲資料夾位置、檔案權限、重新載入。 */
@Composable
fun SettingsScreen(
    state: LibraryState,
    gameRootPath: String,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(title = "設定", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("遊戲資料夾") },
                supportingContent = { Text(gameRootPath) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            ListItem(
                headlineContent = { Text("檔案存取權限") },
                supportingContent = {
                    Text(
                        if (permissionGranted) "已允許" else "尚未允許，App 讀不到歌曲",
                        color = if (permissionGranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                },
                trailingContent = {
                    if (!permissionGranted) OutlinedButton(onClick = onRequestPermission) { Text("允許") }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            ListItem(
                headlineContent = { Text("重新載入歌曲和歌單") },
                supportingContent = {
                    Text(
                        "目前 ${state.songs.size} 首歌、${state.playlists.size} 個歌單" +
                            if (state.brokenSongs.isNotEmpty()) "，${state.brokenSongs.size} 首讀不到" else "",
                    )
                },
                trailingContent = {
                    OutlinedButton(onClick = onReload, enabled = !state.loading) {
                        Text(if (state.loading) "讀取中" else "重新載入")
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            ListItem(
                headlineContent = { Text("版本") },
                supportingContent = { Text("PlaylistMirror ${BuildConfig.VERSION_NAME}") },
            )
        }
    }
}

/** 還沒有檔案權限時顯示的畫面，說明為什麼需要權限。 */
@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("需要檔案存取權限", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "PlaylistMirror 要讀寫 Beat Saber 的歌曲和歌單資料夾，所以需要「所有檔案存取」權限。\n\n" +
                "按下按鈕後會打開系統設定，請把 PlaylistMirror 的開關打開，再回到這裡。",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("前往允許") }
    }
}
