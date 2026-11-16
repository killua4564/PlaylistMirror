package app.playlistmirror.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App 的配色。規格決定只有深色主題，所以只定義一套深色。
 * 主色的藍、輔色的紅，取自圖示 A（藍色代表 BeatSaver、紅色代表 Quest）。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CA8FF),
    onPrimary = Color(0xFF04203F),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFFF5C7A),
    onSecondary = Color(0xFF3F0613),
    background = Color(0xFF0C0E1A),
    onBackground = Color(0xFFE6E8F2),
    surface = Color(0xFF0C0E1A),
    onSurface = Color(0xFFE6E8F2),
    surfaceVariant = Color(0xFF1C2033),
    onSurfaceVariant = Color(0xFFA9AEC4),
    surfaceContainer = Color(0xFF14172A),
    outline = Color(0xFF3A4060),
    error = Color(0xFFFF6B6B),
)

@Composable
fun PlaylistMirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
