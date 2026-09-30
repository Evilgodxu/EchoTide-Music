package com.yichao.evilgodxu.utils

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 文本格式化复核：字节数按 1024 进制递进、兆字节按 10 进制与 2 进制两种口径换算、毫秒按 m:ss 截断。
 *
 * 三个字节数格式化函数均走 `String.format`，小数分隔符随默认语言区域变化，
 * 故测试固定区域为 US，断言的才是格式本身而非运行环境。
 */
class FormatTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun bytesBelowUnitStayInBytes() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1 B", formatBytes(1))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun bytesAdvanceByPowersOf1024() {
        assertEquals("1.00 KB", formatBytes(1024))
        assertEquals("1.00 MB", formatBytes(1024 * 1024))
        assertEquals("1.00 GB", formatBytes(1024 * 1024 * 1024))
        assertEquals("1.00 TB", formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun bytesKeepTwoDecimals() {
        assertEquals("1.50 KB", formatBytes(1536))
        assertEquals("2.25 MB", formatBytes((2.25 * 1024 * 1024).toLong()))
    }

    @Test
    fun bytesAboveLargestUnitStayInThatUnit() {
        // 单位表末尾之后不再递进：TB 之上仍以 TB 表示，只是数值继续增大
        assertEquals("1024.00 TB", formatBytes(1024L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun megabytesUseDecimalPowers() {
        assertEquals("0.00 MB", formatMegabytes(0))
        assertEquals("1.00 MB", formatMegabytes(1_000_000))
        // 同一字节数在两种口径下数值不同：5 MiB 的 5242880 字节只有 5.24 MB
        assertEquals("5.24 MB", formatMegabytes(5242880))
    }

    @Test
    fun mebibytesUseBinaryPowers() {
        assertEquals("0.00 MiB", formatMebibytes(0))
        assertEquals("1.00 MiB", formatMebibytes(1024 * 1024))
        assertEquals("5.00 MiB", formatMebibytes(5242880))
    }

    @Test
    fun timeIsTruncatedToWholeSeconds() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:00", formatTime(999))
        assertEquals("0:01", formatTime(1_000))
        assertEquals("0:59", formatTime(59_999))
    }

    @Test
    fun timeRollsOverToMinutes() {
        assertEquals("1:00", formatTime(60_000))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("10:09", formatTime(609_000))
        // 超过一小时不单独给小时段：播放器进度条只显示到分钟
        assertEquals("60:00", formatTime(3_600_000))
    }
}
