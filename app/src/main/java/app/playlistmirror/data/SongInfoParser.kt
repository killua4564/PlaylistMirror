package app.playlistmirror.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * 從 Info.dat 讀出來的歌曲資料。
 *
 * @property filesToHash 計算歌曲指紋時，除了 Info.dat 以外還要依序算進去的檔案
 * @property audioFileName 音樂檔名稱，舊格式歌曲要靠它量出時間長度
 */
data class ParsedInfo(
    val title: String,
    val subTitle: String?,
    val songAuthor: String?,
    val mapper: String?,
    val difficulties: List<Difficulty>,
    val filesToHash: List<String>,
    val audioFileName: String?,
    val durationSeconds: Int?,
)

/**
 * 讀懂每首歌資料夾裡的 Info.dat（歌曲的「說明書」）。
 *
 * Beat Saber 有兩種說明書格式：
 * - 舊格式（v2，欄位名稱以底線開頭，例如 `_songName`），大部分歌曲是這種
 * - 新格式（v4，欄位放在 `song`、`audio` 等區塊裡），較新的歌曲才有
 */
object SongInfoParser {

    fun parse(text: String): ParsedInfo {
        val root = Json.parseToJsonElement(text).jsonObject
        return when {
            root.containsKey("_songName") -> parseV2(root)
            root.containsKey("song") -> parseV4(root)
            else -> throw IllegalArgumentException("看不懂的 Info.dat 格式")
        }
    }

    /** 舊格式。難度藏在「難度組（例如單手、360 度）→ 各難度」兩層裡面。 */
    private fun parseV2(root: JsonObject): ParsedInfo {
        val beatmaps = root.arr("_difficultyBeatmapSets")
            .mapNotNull { it as? JsonObject }
            .flatMap { set -> set.arr("_difficultyBeatmaps").mapNotNull { it as? JsonObject } }

        return ParsedInfo(
            title = root.str("_songName").blankToNull() ?: "（沒有歌名）",
            subTitle = root.str("_songSubName").blankToNull(),
            songAuthor = root.str("_songAuthorName").blankToNull(),
            mapper = root.str("_levelAuthorName").blankToNull(),
            difficulties = beatmaps.mapNotNull { Difficulty.parse(it.str("_difficulty")) }.distinct().sorted(),
            // 舊格式的指紋 = Info.dat + 每個難度的譜面檔，依說明書裡的順序
            filesToHash = beatmaps.mapNotNull { it.str("_beatmapFilename") },
            audioFileName = root.str("_songFilename"),
            // 舊格式說明書裡沒有時間長度，之後要量音樂檔
            durationSeconds = null,
        )
    }

    /** 新格式。時間長度直接寫在 audio.songDuration。 */
    private fun parseV4(root: JsonObject): ParsedInfo {
        val song = root.obj("song")
        val audio = root.obj("audio")
        val beatmaps = root.arr("difficultyBeatmaps").mapNotNull { it as? JsonObject }

        // 新格式的製譜者寫在每個難度裡，可能有好幾位，把不重複的名字串起來
        val mappers = beatmaps.flatMap { beatmap ->
            beatmap.obj("beatmapAuthors")?.arr("mappers")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        }.filter { it.isNotBlank() }.distinct()

        // 新格式的指紋算法，依 BeatSaver 原始碼：
        // Info.dat + 音訊資料檔 + 每個難度的（譜面檔、燈光檔）。
        // 注意：燈光檔是否計入、順序是否完全正確，還需要用真實歌曲驗證。
        val files = buildList {
            audio?.str("audioDataFilename")?.let { add(it) }
            beatmaps.forEach { beatmap ->
                beatmap.str("beatmapDataFilename")?.let { add(it) }
                beatmap.str("lightshowDataFilename")?.let { add(it) }
            }
        }

        return ParsedInfo(
            title = song?.str("title").blankToNull() ?: "（沒有歌名）",
            subTitle = song?.str("subTitle").blankToNull(),
            songAuthor = song?.str("author").blankToNull(),
            mapper = mappers.joinToString(", ").blankToNull(),
            difficulties = beatmaps.mapNotNull { Difficulty.parse(it.str("difficulty")) }.distinct().sorted(),
            filesToHash = files,
            audioFileName = audio?.str("songFilename"),
            durationSeconds = (audio?.get("songDuration") as? JsonPrimitive)?.doubleOrNull?.roundToInt(),
        )
    }
}
