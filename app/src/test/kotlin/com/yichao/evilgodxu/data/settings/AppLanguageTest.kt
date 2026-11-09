package com.yichao.evilgodxu.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

// 语言持久化值解析：未知值与缺键都必须回落到跟随系统，否则升级后界面语言会变成空文案
class AppLanguageTest {

    @Test
    fun `已知标签解析为对应语言`() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
    }

    @Test
    fun `空标签与缺键回落到跟随系统`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
    }

    @Test
    fun `未知标签回落到跟随系统`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("zh-CN"))
    }

    @Test
    fun `解析结果写回标签后仍解析为原语言`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.languageTag))
        }
    }
}
