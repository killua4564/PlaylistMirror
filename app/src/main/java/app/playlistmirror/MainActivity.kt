package app.playlistmirror

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import app.playlistmirror.ui.AppRoot
import app.playlistmirror.ui.LibraryViewModel
import app.playlistmirror.ui.PlaylistMirrorTheme

/** App 的入口。打開 App 時系統會先執行這裡。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 讓畫面延伸到狀態列底下，並固定使用深色狀態列
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PlaylistMirrorTheme {
                val viewModel: LibraryViewModel = viewModel()
                AppRoot(viewModel)
            }
        }
    }
}
