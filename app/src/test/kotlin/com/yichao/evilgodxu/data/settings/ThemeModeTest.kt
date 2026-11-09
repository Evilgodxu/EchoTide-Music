package com.yichao.evilgodxu.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

// 主题持久化值解析：未知值回落到跟随系统，避免升级后主题停在半截状态
class ThemeModeTest {

    @Test
    fun `已知取值解析为对应主题`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromValue("system"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromValue("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromValue("light"))
    }

    @Test
    fun `未知取值回落到跟随系统`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromValue(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromValue("amoled"))
    }

    @Test
    fun `取值写回后仍解析为原主题`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromValue(mode.value))
        }
    }
}
