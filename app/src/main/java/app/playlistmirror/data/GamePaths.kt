package app.playlistmirror.data

import android.os.Environment
import java.io.File

/**
 * 遊戲資料在 Quest 上的位置。
 * 這些路徑參考原作者 App 的行為，Beat Saber 1.43 加上模組後就是這個結構。
 */
object GamePaths {

    /** 遊戲模組資料的根目錄：/sdcard/ModData/com.beatgames.beatsaber */
    @Suppress("DEPRECATION")
    fun root(): File = File(Environment.getExternalStorageDirectory(), "ModData/com.beatgames.beatsaber")

    /**
     * 歌曲資料夾。1.35 以後用 SongCore；舊的 SongLoader 也一起讀，
     * 以防以前裝的歌還留在那裡。
     */
    const val SONG_CORE = "Mods/SongCore/CustomLevels"
    const val SONG_LOADER = "Mods/SongLoader/CustomLevels"

    /** 歌單資料夾 */
    const val PLAYLISTS = "Mods/PlaylistManager/Playlists"
}
