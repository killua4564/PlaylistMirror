@file:OptIn(ExperimentalMaterial3Api::class)

package app.playlistmirror.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.playlistmirror.data.Difficulty

// 這個檔案放好幾個畫面共用的小零件。

/**
 * 每個畫面共用的外框：上方標題列 + 內容。
 * 底部導覽列由最外層負責，這裡不重複處理邊距（WindowInsets(0)）。
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** 難度小標籤，例如「E」「Ex」「E+」。依難度由易到難上色。 */
@Composable
fun DifficultyBadges(difficulties: List<Difficulty>, long: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in difficulties) {
            Surface(
                color = difficultyColor(d).copy(alpha = 0.18f),
                contentColor = difficultyColor(d),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = if (long) d.label else d.short,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 沿用 Beat Saber 的慣例配色：綠、藍、橘、紅、紫。 */
private fun difficultyColor(d: Difficulty) = when (d) {
    Difficulty.Easy -> androidx.compose.ui.graphics.Color(0xFF4ADE80)
    Difficulty.Normal -> androidx.compose.ui.graphics.Color(0xFF60A5FA)
    Difficulty.Hard -> androidx.compose.ui.graphics.Color(0xFFFBBF24)
    Difficulty.Expert -> androidx.compose.ui.graphics.Color(0xFFF87171)
    Difficulty.ExpertPlus -> androidx.compose.ui.graphics.Color(0xFFC084FC)
}

/** 清單是空的時候，置中顯示一段說明文字。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 確認視窗：用在刪除這類無法復原的動作。 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 區塊小標題。 */
@Composable
fun ColumnScope.SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
