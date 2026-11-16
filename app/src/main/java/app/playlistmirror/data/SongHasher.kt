package app.playlistmirror.data

import java.io.File
import java.security.MessageDigest

/**
 * 計算歌曲指紋（SHA-1）。
 *
 * 比喻：把 Info.dat 和指定的檔案，依固定順序全部倒進一台「攪拌機」，
 * 最後得到一串 40 字元的編號。只要任何一個檔案改了一個字，編號就完全不同。
 * BeatSaver、遊戲、歌單都用同一套算法，所以同一首歌在三邊的指紋會一樣。
 */
object SongHasher {

    fun hash(folder: File, infoFile: File, filesToHash: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(infoFile.readBytes())
        for (name in filesToHash) {
            val file = File(folder, name)
            // 檔案不存在就跳過（壞掉的歌曲）；指紋會和 BeatSaver 不同，但 App 不會當掉
            if (file.isFile) digest.update(file.readBytes())
        }
        // 把結果轉成 40 個小寫十六進位字元
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
