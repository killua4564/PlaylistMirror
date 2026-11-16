package app.playlistmirror.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.playlistmirror.data.Playlist
import app.playlistmirror.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 封面圖的「小圖暫存區」。
 *
 * 為什麼需要：清單上下捲動時，同一張封面會一直出現又消失。
 * 每次都重新讀檔、解碼很慢也耗電，所以把縮小後的圖記在記憶體裡，下次直接拿。
 * LruCache 像一個固定大小的抽屜：放滿了就把最久沒用的那張丟掉。
 */
private object CoverCache {
    /** 清單上顯示的大小約 56dp，解碼成最長邊約 160 像素就夠清楚，又不佔記憶體。 */
    const val TARGET_PX = 160

    // 抽屜大小用「位元組」計算：最多 16MB，大約可以放 150 張小封面
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, image: ImageBitmap) { cache.put(key, image) }

    /**
     * 把圖片位元組解碼成縮小的圖。
     * 先只讀圖片的長寬（不真的解碼），算出要縮小幾倍，再用縮小倍率解碼，省記憶體。
     */
    fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}

/** 歌曲封面：讀歌曲資料夾裡的封面圖檔。沒有封面就顯示音符圖示。 */
@Composable
fun SongCover(song: Song, size: Dp = 56.dp) {
    val file = song.coverFile
    CoverImage(
        // 用「檔案路徑＋修改時間」當鑰匙：歌曲更新、封面換了，就會重新讀
        cacheKey = file?.let { "song:${it.absolutePath}:${it.lastModified()}" },
        load = { file?.let { readFile(it) } },
        fallback = Icons.Default.MusicNote,
        size = size,
    )
}

/** 沒有歌曲資料可用時（例如歌單裡有、但沒裝在 Quest 上的歌）顯示的灰色方塊。 */
@Composable
fun MissingCover(size: Dp = 56.dp) {
    CoverImage(cacheKey = null, load = { null }, fallback = Icons.Default.MusicNote, size = size)
}

/** 歌單封面：歌單檔裡的 image 欄位（Base64 文字）。沒有封面就顯示歌單圖示。 */
@Composable
fun PlaylistCover(playlist: Playlist, size: Dp = 56.dp) {
    val base64 = playlist.imageBase64
    CoverImage(
        // 用「歌單檔路徑＋圖片文字的長度與開頭」當鑰匙，封面換了就會重新解碼
        cacheKey = base64?.let { "playlist:${playlist.file.absolutePath}:${it.length}:${it.hashCode()}" },
        load = { base64?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } },
        fallback = Icons.AutoMirrored.Filled.QueueMusic,
        size = size,
    )
}

/**
 * 共用的封面方塊：先看暫存區有沒有，沒有就在背景讀圖（不卡住畫面），讀到後換上去。
 * 讀不到或沒有封面時，顯示灰底加圖示。
 */
@Composable
private fun CoverImage(cacheKey: String?, load: () -> ByteArray?, fallback: ImageVector, size: Dp) {
    var image by remember(cacheKey) { mutableStateOf(cacheKey?.let { CoverCache.get(it) }) }
    LaunchedEffect(cacheKey) {
        if (cacheKey == null || image != null) return@LaunchedEffect
        // Dispatchers.IO：讀檔和解碼放到背景的工作人員去做
        image = withContext(Dispatchers.IO) {
            runCatching { load()?.let { CoverCache.decode(it) } }.getOrNull()
        }?.also { CoverCache.put(cacheKey, it) }
    }

    val shape = RoundedCornerShape(6.dp)
    val current = image
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            contentScale = ContentScale.Crop, // 不是正方形的圖，裁掉多的部分，填滿方塊
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun readFile(file: File): ByteArray? = if (file.isFile) file.readBytes() else null
