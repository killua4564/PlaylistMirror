package app.playlistmirror.ui

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.playlistmirror.data.StorageAccess

/** 底部導覽列的三個分頁。 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Songs("songs", "歌曲", Icons.Default.MusicNote),
    Playlists("playlists", "歌單", Icons.AutoMirrored.Filled.QueueMusic),
    Sync("sync", "同步", Icons.Default.Sync),
}

/**
 * App 的最外層：檢查權限、底部導覽列、以及各畫面之間怎麼切換（導航）。
 *
 * 導航可以想成網址：「songs」是歌曲分頁，「song/指紋」是某首歌的頁面，
 * 切換畫面就是「前往某個網址」，按返回就是「回上一頁」。
 */
@Composable
fun AppRoot(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.isGranted(context)) }

    // 從系統設定頁回來時，重新檢查權限；剛拿到權限就開始讀歌
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = StorageAccess.isGranted(context)
        if (now && !granted) viewModel.reload()
        granted = now
    }

    // Android 10 的舊式權限視窗
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = StorageAccess.isGranted(context)
        if (granted) viewModel.reload()
    }
    val requestPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.startActivity(StorageAccess.settingsIntent(context))
        } else {
            legacyLauncher.launch(StorageAccess.legacyPermissions)
        }
    }

    if (!granted) {
        PermissionScreen(onRequestPermission = requestPermission)
        return
    }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = Tab.entries.any { it.route == currentRoute }
    val openSettings = { nav.navigate("settings") }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                // 切換分頁時不要一直疊加頁面，並保留各分頁的捲動位置
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Songs.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Songs.route) {
                SongListScreen(
                    state = state,
                    onOpenSong = { nav.navigate("song/${it.hash}") },
                    onOpenSettings = openSettings,
                )
            }
            composable(Tab.Playlists.route) {
                PlaylistListScreen(
                    state = state,
                    onOpenPlaylist = { nav.navigate("playlist/${Uri.encode(it.file.absolutePath)}") },
                    onOpenSettings = openSettings,
                )
            }
            composable(Tab.Sync.route) {
                SyncScreen(onOpenSettings = openSettings)
            }
            composable("song/{hash}") { entry ->
                SongDetailScreen(
                    state = state,
                    hash = entry.arguments?.getString("hash").orEmpty(),
                    onSavePlaylists = viewModel::setSongPlaylists,
                    onDelete = viewModel::deleteSong,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("playlist/{path}") { entry ->
                PlaylistDetailScreen(
                    state = state,
                    filePath = Uri.decode(entry.arguments?.getString("path").orEmpty()),
                    onRemove = viewModel::removeEntry,
                    onOpenSong = { nav.navigate("song/$it") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    gameRootPath = viewModel.gameRootPath,
                    permissionGranted = granted,
                    onRequestPermission = requestPermission,
                    onReload = viewModel::reload,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
