package com.yichao.evilgodxu.data.music.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 代理音源时长解析：规范约定毫秒，但音源常直接透传平台的展示值。
 *
 * 误判的代价不对称：解析失败只是少一项时长，把 `"04:29"` 当成 0 或把 `"269"` 当成秒，
 * 会让曲目时长错到离谱，进而影响试听判定与排序。
 */
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
