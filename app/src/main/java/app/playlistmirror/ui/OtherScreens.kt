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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
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

/** 設定頁：BeatSaver 帳號、遊戲資料夾位置、檔案權限、重新載入。 */
@Composable
fun SettingsScreen(
    state: LibraryState,
    auth: AuthUiState,
    gameRootPath: String,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onReload: () -> Unit,
    onLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(title = "設定", onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            AccountItem(auth, onLogin, onCancelLogin, onLogout)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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

/** 設定頁的「BeatSaver 帳號」一列，依登入狀態顯示不同內容。 */
@Composable
private fun AccountItem(auth: AuthUiState, onLogin: () -> Unit, onCancelLogin: () -> Unit, onLogout: () -> Unit) {
    val display: Pair<String, Pair<String, () -> Unit>?> = when (auth) {
        AuthUiState.NotConfigured -> "這個版本沒有 BeatSaver 憑證，無法登入" to null
        AuthUiState.LoggedOut -> "尚未登入。登入後才能讀取私人歌單、把變更寫回 BeatSaver" to ("登入" to onLogin)
        AuthUiState.Requesting -> "正在申請登入代碼…" to null
        is AuthUiState.Waiting -> "等待你在手機上輸入代碼…" to ("取消" to onCancelLogin)
        is AuthUiState.LoggedIn -> "已登入：${auth.name}" to ("登出" to onLogout)
        is AuthUiState.Error -> auth.message to ("重試" to onLogin)
    }
    val (status, button) = display
    ListItem(
        headlineContent = { Text("BeatSaver 帳號") },
        supportingContent = {
            Text(
                status,
                color = if (auth is AuthUiState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            button?.let { (label, action) -> OutlinedButton(onClick = action) { Text(label) } }
        },
    )
    if (auth is AuthUiState.Waiting) LoginCodeDialog(auth, onCancelLogin)
}

/**
 * 裝置代碼登入的視窗：顯示網址和 8 字代碼，請你用手機或電腦完成登入。
 * 你在網頁上按下允許後，App 會自動偵測、關掉這個視窗。
 */
@Composable
private fun LoginCodeDialog(state: AuthUiState.Waiting, onCancel: () -> Unit) {
    val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(state.expiresAt))
    AlertDialog(
        onDismissRequest = {}, // 不讓點旁邊就關掉，避免誤觸中斷登入
        title = { Text("登入 BeatSaver") },
        text = {
            SelectionContainer {
                Column {
                    Text("1. 用手機或電腦打開這個網址：")
                    Text(
                        state.code.verificationUrl.removePrefix("https://"),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text("2. 輸入這組代碼：")
                    Text(
                        state.code.userCode,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text("3. 登入 BeatSaver 並按「允許」。完成後這裡會自動登入。")
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "代碼在 $until 前有效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
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
