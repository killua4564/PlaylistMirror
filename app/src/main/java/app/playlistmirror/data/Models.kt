package app.playlistmirror.data

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 難度。Beat Saber 的譜面有五種難度，由易到難排列。
 * 列舉（enum）的先後順序就是難度高低，排序時會直接用到這個順序。
 *
 * @property label 顯示在歌曲頁面的完整名稱
 * @property short 顯示在清單裡的縮寫，節省空間
 */
enum class Difficulty(val label: String, val short: String) {
    Easy("Easy", "E"),
    Normal("Normal", "N"),
    Hard("Hard", "H"),
    Expert("Expert", "Ex"),
    ExpertPlus("Expert+", "E+");

    companion object {
        /** 把檔案裡寫的難度文字（例如 "ExpertPlus"）轉成這個列舉；認不得就回傳 null。 */
        fun parse(text: String?): Difficulty? =
            entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

/**
 * Quest 上已安裝的一首自訂歌曲。
 * 「data class」可以想成一張資料卡：只裝資料，不做事。
 */
data class Song(
    /** 歌曲指紋（40 個小寫十六進位字元）。歌單就是靠它認出是哪一首歌。 */
    val hash: String,
    /** 歌曲所在的資料夾。刪除歌曲時就是刪掉這個資料夾。 */
    val folder: File,
    val title: String,
    val subTitle: String?,
    val songAuthor: String?,
    val mapper: String?,
    val difficulties: List<Difficulty>,
    /** 時間長度（秒）；拿不到就是 null，畫面會顯示「—」。 */
    val durationSeconds: Int?,
    /** 下載時間：用歌曲資料夾的修改時間代表（毫秒）。 */
    val downloadedAt: Long,
    /** BeatSaver 上的短 ID（例如 "3b2c"），從資料夾名稱推測，可能沒有。 */
    val key: String?,
    /** 封面圖檔；說明書沒寫或檔案不存在就是 null，畫面顯示預設圖示。 */
    val coverFile: File? = null,
)

/**
 * 歌單裡的一筆歌曲紀錄。
 * raw 保留這筆紀錄在檔案裡的「原始內容」，這樣存檔時不會弄丟我們不認得的欄位
 * （例如其他模組寫進去的難度設定）。
 */
data class PlaylistEntry(
    /** 歌曲指紋（已轉小寫）。遊戲內建歌曲沒有指紋，這時是 null。 */
    val hash: String?,
    val songName: String?,
    val raw: JsonObject,
    /** BeatSaver 短 ID（已轉小寫）。歌曲更新後指紋會變、短 ID 不變，同步比對靠它認出同一首歌。 */
    val key: String? = null,
)

/**
 * 一個歌單檔案。
 * raw 一樣保留整個檔案的原始內容（封面圖、說明、其他模組的資料），存檔時原封不動寫回。
 */
data class Playlist(
    val file: File,
    val title: String,
    val entries: List<PlaylistEntry>,
    val raw: JsonObject,
) {
    /** 這個歌單包含的所有歌曲指紋，用來快速判斷「某首歌在不在這個歌單裡」。 */
    val hashes: Set<String> = entries.mapNotNull { it.hash }.toSet()

    /** 對應的 BeatSaver 歌單 ID，從 customData.syncURL 取得；沒有就是 null（不參與同步）。 */
    val cloudId: String? = PlaylistLink.idFromSyncUrl((raw["customData"] as? JsonObject)?.str("syncURL"))

    /**
     * 歌單封面：把圖片轉成文字（Base64）存在歌單檔裡。
     * 欄位名稱有兩種：.bplist 檔用 image，.json 檔（例如 Quest Song Manager 建的）用 imageString，
     * Beat Saber 兩種都認得，所以這裡也兩種都讀。
     * 有些檔案會在前面加「data:image/png;base64,」，這裡把它去掉，只留圖片內容。
     */
    val imageBase64: String? = listOf("image", "imageString")
        .firstNotNullOfOrNull { field -> raw.str(field)?.substringAfter("base64,")?.takeIf { it.isNotBlank() } }
}

/** 讀取失敗的檔案，以及失敗原因。畫面上會提示，讓使用者知道有東西沒讀到。 */
data class BrokenFile(val file: File, val reason: String)
