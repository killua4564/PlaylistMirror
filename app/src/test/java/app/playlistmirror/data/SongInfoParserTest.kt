package app.playlistmirror.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌曲說明書（Info.dat）的解讀。對應測項 T1-01、T1-03。
 */
class SongInfoParserTest {

    @Test
    fun `T1-01 舊格式：讀出歌名、製譜者、難度，並依說明書順序列出要算指紋的檔案`() {
        val info = SongInfoParser.parse(
            """
            {"_version":"2.0.0","_songName":"Neon","_songSubName":"Remix","_songAuthorName":"Artist",
             "_levelAuthorName":"MapperA","_songFilename":"song.egg",
             "_difficultyBeatmapSets":[
               {"_beatmapCharacteristicName":"Standard","_difficultyBeatmaps":[
                 {"_difficulty":"Expert","_beatmapFilename":"ExpertStandard.dat"},
                 {"_difficulty":"Easy","_beatmapFilename":"EasyStandard.dat"}]},
               {"_beatmapCharacteristicName":"OneSaber","_difficultyBeatmaps":[
                 {"_difficulty":"Expert","_beatmapFilename":"ExpertOneSaber.dat"}]}]}
            """.trimIndent(),
        )
        assertEquals("Neon", info.title)
        assertEquals("Remix", info.subTitle)
        assertEquals("Artist", info.songAuthor)
        assertEquals("MapperA", info.mapper)
        // 難度去除重複，並由易到難排列
        assertEquals(listOf(Difficulty.Easy, Difficulty.Expert), info.difficulties)
        // 指紋檔案保持說明書裡的順序，不能重新排序
        assertEquals(listOf("ExpertStandard.dat", "EasyStandard.dat", "ExpertOneSaber.dat"), info.filesToHash)
        // T1-03 舊格式沒有寫時間長度，不另外量音樂檔
        assertNull(info.durationSeconds)
    }

    @Test
    fun `T1-01 舊格式：空白的欄位當成沒有`() {
        val info = SongInfoParser.parse(
            """{"_songName":"","_songSubName":"  ","_levelAuthorName":"","_difficultyBeatmapSets":[]}""",
        )
        assertEquals("（沒有歌名）", info.title)
        assertNull(info.subTitle)
        assertNull(info.mapper)
        assertEquals(emptyList<Difficulty>(), info.difficulties)
    }

    @Test
    fun `T1-01 新格式：讀出歌名、多位製譜者，指紋檔案依序為音訊資料、每個難度的譜面與燈光`() {
        val info = SongInfoParser.parse(
            """
            {"version":"4.0.1","song":{"title":"Future","subTitle":"","author":"Artist"},
             "audio":{"songFilename":"song.ogg","songDuration":183.6,"audioDataFilename":"AudioData.dat"},
             "difficultyBeatmaps":[
               {"difficulty":"Hard","beatmapAuthors":{"mappers":["A","B"]},
                "beatmapDataFilename":"Hard.beatmap.dat","lightshowDataFilename":"Hard.lightshow.dat"},
               {"difficulty":"ExpertPlus","beatmapAuthors":{"mappers":["B"]},
                "beatmapDataFilename":"EP.beatmap.dat","lightshowDataFilename":"EP.lightshow.dat"}]}
            """.trimIndent(),
        )
        assertEquals("Future", info.title)
        assertNull(info.subTitle)
        assertEquals("A, B", info.mapper)
        assertEquals(listOf(Difficulty.Hard, Difficulty.ExpertPlus), info.difficulties)
        assertEquals(
            listOf("AudioData.dat", "Hard.beatmap.dat", "Hard.lightshow.dat", "EP.beatmap.dat", "EP.lightshow.dat"),
            info.filesToHash,
        )
    }

    @Test
    fun `T1-03 新格式：時間長度取自說明書並四捨五入到秒`() {
        val info = SongInfoParser.parse(
            """{"song":{"title":"X"},"audio":{"songDuration":183.6},"difficultyBeatmaps":[]}""",
        )
        assertEquals(184, info.durationSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `T1-02 看不懂的格式要回報錯誤，而不是讀出錯的資料`() {
        SongInfoParser.parse("""{"something":"else"}""")
    }

    @Test(expected = Exception::class)
    fun `T1-02 壞掉的 JSON 要回報錯誤`() {
        SongInfoParser.parse("{ 這不是 JSON")
    }
}
