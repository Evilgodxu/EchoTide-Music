package com.yichao.evilgodxu.data.music.metadata

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中转残留回收复核：这条规则两头都可能出错，且两头都不报错 ——
 * 判据放宽一点会删掉此刻正在写入的那份（写入方随后的改名静默失败），
 * 收紧一点残留就永远留在盘上（本次回收白做）。故在此把边界钉死。
 */
class TempResidueTest {

    private companion object {
        const val SUFFIX = ".tmp"
        const val GRACE_MS = 60_000L
        const val BEYOND_GRACE_MS = GRACE_MS + 1_000L
    }

    private val dir: File = Files.createTempDirectory("temp-residue").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `后缀匹配且已过宽限期的残留被回收`() {
        val residue = residue("large_cover_1a2b.webp$SUFFIX", ageMs = BEYOND_GRACE_MS)
        assertEquals(1, reclaimTempResidueIn(dir, SUFFIX, GRACE_MS))
        assertFalse(residue.exists())
    }

    @Test
    fun `宽限期内的残留保留`() {
        // 可能正是此刻在写的那个：删掉会让写入方的改名静默失败
        val writing = residue("cover_2c3d.jpg$SUFFIX", ageMs = 0L)
        assertEquals(0, reclaimTempResidueIn(dir, SUFFIX, GRACE_MS))
        assertTrue(writing.exists())
    }

    @Test
    fun `后缀不匹配的落盘封面不受影响`() {
        val cover = residue("cover_4e5f.jpg", ageMs = BEYOND_GRACE_MS)
        assertEquals(0, reclaimTempResidueIn(dir, SUFFIX, GRACE_MS))
        assertTrue(cover.exists())
    }

    @Test
    fun `目录不存在时按无可回收处理`() {
        assertEquals(0, reclaimTempResidueIn(File(dir, "absent"), SUFFIX, GRACE_MS))
    }

    private fun residue(name: String, ageMs: Long): File =
        File(dir, name).apply {
            writeText("x")
            setLastModified(System.currentTimeMillis() - ageMs)
        }
}
