package com.yichao.evilgodxu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 版本比较决定是否会提示更新：判错会让用户被反复提示同一版本，或永远收不到新版本
class AppVersionTest {

    @Test
    fun `补丁号更高时判定为新版本`() {
        assertTrue(UpdateManager.isNewerVersion("1.2.4", "1.2.3"))
    }

    @Test
    fun `版本相同时不判定为新版本`() {
        assertFalse(UpdateManager.isNewerVersion("6.2.3", "6.2.3"))
    }

    @Test
    fun `缺省段按零补齐`() {
        assertFalse(UpdateManager.isNewerVersion("1.2", "1.2.0"))
        assertTrue(UpdateManager.isNewerVersion("1.2.1", "1.2"))
    }

    @Test
    fun `按整数逐段比较而非字符串比较`() {
        assertTrue(UpdateManager.isNewerVersion("1.10.0", "1.9.0"))
        assertFalse(UpdateManager.isNewerVersion("1.9.0", "1.10.0"))
    }

    @Test
    fun `更高位的版本号优先`() {
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(UpdateManager.isNewerVersion("1.99.99", "2.0.0"))
    }

    @Test
    fun `非数字段不参与比较`() {
        assertFalse(UpdateManager.isNewerVersion("1.x.0", "1.0.0"))
        assertTrue(UpdateManager.isNewerVersion("1.x.1", "1.0.0"))
    }

    @Test
    fun `归一化去掉空白与版本前缀`() {
        assertEquals("1.2.3", UpdateManager.normalizeVersion(" v1.2.3 "))
        assertEquals("2.0.0", UpdateManager.normalizeVersion("V2.0.0"))
        assertEquals("6.2.3", UpdateManager.normalizeVersion("6.2.3"))
    }
}
