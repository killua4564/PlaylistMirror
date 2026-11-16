package app.playlistmirror.data

import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.roundToInt

/**
 * 量音樂檔的長度（秒）。
 *
 * 舊格式歌曲的說明書裡沒寫時間長度，只能請 Android 內建的「媒體資訊讀取器」看音樂檔。
 * 歌曲的音樂檔副檔名是 .egg，其實就是 .ogg 改名，讀取器會自己認出內容格式。
 * 讀不到就回傳 null，畫面會顯示「—」，不會當掉。
 */
object AudioDuration {

    fun readSeconds(file: File): Int? {
        if (!file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { millis -> (millis / 1000.0).roundToInt() }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
