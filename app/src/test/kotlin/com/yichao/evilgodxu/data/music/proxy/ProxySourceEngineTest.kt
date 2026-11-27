package com.yichao.evilgodxu.data.music.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

/** 基地址与占位符都不得吞掉或拆坏参数 */
class ProxySourceEngineTest {

    @Test
    fun `已有查询串不丢失附加参数`() {
        val action = action(url = "https://example.com/search?token=x", params = mapOf("keyword" to "{keyword}"))
        val url = ProxySourceEngine.buildUrl(action, mapOf("keyword" to "hello"))
        assertEquals("https://example.com/search?token=x&keyword=hello", url)
    }

    @Test
    fun `占位值按 URL 组件编码`() {
        val action = action(url = "https://example.com/q?q={keyword}")
        val url = ProxySourceEngine.buildUrl(action, mapOf("keyword" to "A&B#C"))
        assertEquals("https://example.com/q?q=A%26B%23C", url)
    }

    private fun action(
        url: String,
        method: String = "GET",
        params: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ) = ProxyActionSpec(
        method = method,
        url = url,
        params = params,
        query = query,
        headers = emptyMap(),
        qualities = emptyMap(),
        result = ProxyResultSpec(),
    )
}
