package com.yichao.evilgodxu.data.music.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 时长解析：失败少一项，误判则错到离谱 */
class ProxyDurationTest {

    @Test
    fun `冒号形式按分秒解析`() {
        assertEquals(269_000L, parseProxyDurationMs("04:29"))
        assertEquals(269_000L, parseProxyDurationMs("4:29"))
        assertEquals(3_723_000L, parseProxyDurationMs("01:02:03"))
    }

    @Test
    fun `纯数字按规范的毫秒处理`() {
        assertEquals(269_000L, parseProxyDurationMs("269000"))
        assertEquals(269_000L, parseProxyDurationMs(269_000L))
    }

    @Test
    fun `无法识别或非正的时长返回 null`() {
        assertNull(parseProxyDurationMs(null))
        assertNull(parseProxyDurationMs(""))
        assertNull(parseProxyDurationMs("--:--"))
        assertNull(parseProxyDurationMs("00:00"))
        assertNull(parseProxyDurationMs(0L))
        assertNull(parseProxyDurationMs(true))
    }
}
