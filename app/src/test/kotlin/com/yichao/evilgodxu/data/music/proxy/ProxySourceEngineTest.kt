package com.yichao.evilgodxu.data.music.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 代理音源 URL 组装：已有查询参数的基地址不能吞掉新增参数，
 * 模板占位符里的 &/# 也不能把歌名拆成新的参数或片段。
 */
class ProxySourceEngineTest {

    @Test
    fun existingQueryDoesNotDropAdditionalParams() {
        val action = action(url = "https://example.com/search?token=x", params = mapOf("keyword" to "{keyword}"))
        val url = ProxySourceEngine.buildUrl(action, mapOf("keyword" to "hello"))
        assertEquals("https://example.com/search?token=x&keyword=hello", url)
    }

    @Test
    fun placeholderValuesAreEncodedAsUrlComponents() {
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
