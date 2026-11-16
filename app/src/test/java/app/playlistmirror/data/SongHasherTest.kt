package app.playlistmirror.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/**
 * 指紋計算。對應測項 T1-05（算法本身）。
 * 用公開的 SHA-1 標準答案核對，確保「依序把檔案倒進攪拌機」這件事做對了。
 */
class SongHasherTest {

    private val folder = Files.createTempDirectory("hasher").toFile()

    @Test
    fun `只有 Info_dat 時，指紋等於它內容的 SHA-1`() {
        val info = folder.resolve("Info.dat").apply { writeText("abc") }
        // "abc" 的 SHA-1 是公開的標準答案
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SongHasher.hash(folder, info, emptyList()))
    }

    @Test
    fun `多個檔案依序接起來計算`() {
        val info = folder.resolve("Info.dat").apply { writeText("abc") }
        folder.resolve("Expert.dat").writeText("def")
        // 等同 "abcdef" 的 SHA-1
        assertEquals("1f8ac10f23c5b5bc1167bda84b833e5c057a77d2", SongHasher.hash(folder, info, listOf("Expert.dat")))
    }

    @Test
    fun `順序不同，指紋就不同`() {
        val info = folder.resolve("Info.dat").apply { writeText("") }
        folder.resolve("a.dat").writeText("a")
        folder.resolve("b.dat").writeText("b")
        val ab = SongHasher.hash(folder, info, listOf("a.dat", "b.dat"))
        val ba = SongHasher.hash(folder, info, listOf("b.dat", "a.dat"))
        assert(ab != ba)
    }

    @Test
    fun `缺少的檔案會跳過，App 不會當掉`() {
        val info = folder.resolve("Info.dat").apply { writeText("abc") }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SongHasher.hash(folder, info, listOf("missing.dat")))
    }
}
