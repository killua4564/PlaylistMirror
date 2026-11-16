package app.playlistmirror.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.playlistmirror.sync.ItemResult
import app.playlistmirror.sync.Side
import app.playlistmirror.sync.SyncExecutor
import app.playlistmirror.sync.SyncItem
import app.playlistmirror.sync.SyncService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步分頁。
 *
 * 流程：按「同步全部歌單」→ App 讀取兩邊、算出摘要 → 你看過摘要、按「確認同步」→ 執行。
 * 在按「確認同步」之前，不會改動任何東西。
 *
 * @param canWriteBeatSaver 是否已有 BeatSaver 授權；沒有時「BeatSaver 會變成」的項目只顯示、不執行
 */
@Composable
fun SyncScreen(
    state: SyncUiState,
    canWriteBeatSaver: Boolean,
    onStart: () -> Unit,
    onConfirm: (SyncService.Preview, List<SyncItem>) -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ScreenScaffold(
        title = "同步",
        actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is SyncUiState.Idle -> IdleView(state, canWriteBeatSaver, onStart, onOpenSettings)
                SyncUiState.Checking -> CenterMessage("正在讀取 BeatSaver 與 Quest 上的歌單…", progress = true)
                is SyncUiState.Review -> ReviewView(state.preview, canWriteBeatSaver, onConfirm, onCancel)
                is SyncUiState.Running -> RunningView(state)
                is SyncUiState.Error -> CenterMessage(state.message, action = "返回", onAction = onCancel)
            }
        }
    }
}

// ───────────── 待命 ─────────────

@Composable
private fun IdleView(state: SyncUiState.Idle, loggedIn: Boolean, onStart: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "上次同步：" + (state.lastSyncedAt?.let { formatDateTime(it) } ?: "尚未同步"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!loggedIn) {
            // 沒登入時提醒：只能讀公開歌單，變更也寫不回 BeatSaver
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable(onClick = onOpenSettings),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("尚未登入 BeatSaver", color = MaterialTheme.colorScheme.secondary)
                    Text(
                        "只能同步公開歌單，變更也無法寫回。點這裡到設定登入。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("同步全部歌單")
        }
        state.lastResults?.let { results ->
            Spacer(Modifier.height(24.dp))
            ResultSummary(results)
        }
    }
}

/** 剛完成的同步結果：先給一行總結，再列出需要注意的項目。 */
@Composable
private fun ResultSummary(results: List<ItemResult>) {
    val done = results.count { it.status == ItemResult.Status.Done }
    val skipped = results.filter { it.status == ItemResult.Status.Skipped }
    val failed = results.filter { it.status == ItemResult.Status.Failed }
    val warned = results.filter { it.status == ItemResult.Status.Done && it.message != null }

    Text(
        if (results.isEmpty()) "同步完成，兩邊已一致。" else "同步完成：$done 項完成" +
            (if (skipped.isNotEmpty()) "、${skipped.size} 項待授權" else "") +
            (if (failed.isNotEmpty()) "、${failed.size} 項失敗" else ""),
        style = MaterialTheme.typography.titleMedium,
    )
    LazyColumn(Modifier.fillMaxWidth()) {
        items(failed + warned + skipped) { result ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(SyncExecutor.describe(result.item))
                Text(
                    when (result.status) {
                        ItemResult.Status.Failed -> "失敗：${result.message}"
                        ItemResult.Status.Skipped -> "未執行：${result.message}"
                        ItemResult.Status.Done -> "完成，但：${result.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.status == ItemResult.Status.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ───────────── 摘要 ─────────────

@Composable
private fun ReviewView(
    preview: SyncService.Preview,
    canWriteBeatSaver: Boolean,
    onConfirm: (SyncService.Preview, List<SyncItem>) -> Unit,
    onCancel: () -> Unit,
) {
    // 預設全部勾選（包含「請你決定」的項目）；沒授權時 BeatSaver 那邊無法執行，所以不勾
    val checked = remember(preview) {
        mutableStateMapOf<Int, Boolean>().apply {
            preview.items.forEachIndexed { i, item ->
                put(i, item.side == Side.Quest || canWriteBeatSaver)
            }
        }
    }
    val indexed = preview.items.withIndex().toList()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Text(
                    if (preview.items.isEmpty()) "已是最新，兩邊一致。" else "你確定要做這些動作嗎？",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (preview.notVisible.isNotEmpty() || preview.failed.isNotEmpty()) {
                item { Warnings(preview) }
            }
            for (side in Side.entries) {
                val sideItems = indexed.filter { it.value.side == side }
                if (sideItems.isEmpty()) continue
                item {
                    // 區塊標題旁的勾選框：一次勾選或取消「Quest 會變成」（或「BeatSaver 會變成」）底下的所有項目
                    val sideIndexes = sideItems.map { it.index }
                    val sideSelected = sideIndexes.count { checked[it] == true }
                    SectionHeader(
                        if (side == Side.Quest) "Quest 會變成" else "BeatSaver 會變成",
                        note = if (side == Side.BeatSaver && !canWriteBeatSaver) "需要 BeatSaver 授權後才能寫回；這些變更會保留到下次" else null,
                        state = toggleState(sideSelected, sideIndexes.size),
                        enabled = side == Side.Quest || canWriteBeatSaver,
                        onToggle = {
                            val target = sideSelected != sideIndexes.size
                            sideIndexes.forEach { checked[it] = target }
                        },
                    )
                }
                for ((title, group) in sideItems.groupBy { it.value.playlistTitle }) {
                    item {
                        // 歌單標題旁的勾選框：一次勾選或取消這個歌單的所有項目
                        val enabled = side == Side.Quest || canWriteBeatSaver
                        val indexes = group.map { it.index }
                        val selectedCount = indexes.count { checked[it] == true }
                        GroupHeader(
                            title = title,
                            count = indexes.size,
                            state = toggleState(selectedCount, indexes.size),
                            enabled = enabled,
                            onToggle = {
                                // 全勾時按下 → 全部取消；其他情況（沒勾、部分勾）按下 → 全部勾選
                                val target = selectedCount != indexes.size
                                indexes.forEach { checked[it] = target }
                            },
                        )
                    }
                    items(group, key = { it.index }) { (index, item) ->
                        val enabled = item.side == Side.Quest || canWriteBeatSaver
                        ItemRow(
                            item = item,
                            checked = checked[index] == true,
                            enabled = enabled,
                            onCheckedChange = { checked[index] = it },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onCancel) { Text(if (preview.items.isEmpty()) "完成" else "取消") }
            if (preview.items.isNotEmpty()) {
                val selected = preview.items.filterIndexed { i, _ -> checked[i] == true }
                Button(onClick = { onConfirm(preview, selected) }, enabled = selected.isNotEmpty()) {
                    Text("確認同步（${selected.size}）")
                }
            }
        }
    }
}

@Composable
private fun Warnings(preview: SyncService.Preview) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (preview.notVisible.isNotEmpty()) {
                Text("有 ${preview.notVisible.size} 個歌單在 BeatSaver 上看不到，本次略過", color = MaterialTheme.colorScheme.secondary)
                Text(
                    "可能是私人歌單（需要登入 BeatSaver），或已在網站上重建：" +
                        preview.notVisible.joinToString("、") { it.title },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for ((playlist, reason) in preview.failed) {
                Text("「${playlist.title}」讀取失敗：$reason", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 勾了幾項 → 勾選框該長什麼樣：全勾、全不勾、或部分勾（一條橫線）。 */
private fun toggleState(selected: Int, total: Int) = when (selected) {
    0 -> ToggleableState.Off
    total -> ToggleableState.On
    else -> ToggleableState.Indeterminate
}

/** 「Quest 會變成」「BeatSaver 會變成」的區塊標題，左邊有三態勾選框控制整個區塊。 */
@Composable
private fun SectionHeader(text: String, note: String?, state: ToggleableState, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 8.dp, end = 16.dp, top = 20.dp),
    ) {
        TriStateCheckbox(state = state, onClick = onToggle, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** 歌單標題列：三態勾選框（全勾、全不勾、部分勾）＋歌單名稱與項目數。 */
@Composable
private fun GroupHeader(title: String, count: Int, state: ToggleableState, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp),
    ) {
        TriStateCheckbox(state = state, onClick = onToggle, enabled = enabled)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text("$count 項", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemRow(item: SyncItem, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(SyncExecutor.describe(item))
            if (item.needsDecision) {
                Text(
                    "請你決定：第一次同步，無法判斷是哪一邊改的",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

// ───────────── 執行中、訊息 ─────────────

@Composable
private fun RunningView(state: SyncUiState.Running) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("同步中（${state.done} / ${state.total}）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(state.label, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CenterMessage(text: String, progress: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(millis))
