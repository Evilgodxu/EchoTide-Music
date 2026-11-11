package com.yichao.evilgodxu.data.music.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 远程封面内存缓存的容量核算与淘汰顺序。
 *
 * 值用字符串代替位图：本类只关心「占多少字节」与「谁先出局」，与值的真实类型无关。
 */
class SizeBoundedLruTest {

    @Test
    fun `写入后按同键取回`() {
        val cache = SizeBoundedLru<String, String>(maxBytes = 100, sizeOf = { it.length })
        cache.put("a", "aaaa")
        assertSame("aaaa", cache.get("a"))
        assertEquals(4, cache.sizeBytes)
    }

    @Test
    fun `容量不足时先丢最久未读的一项`() {
        val cache = SizeBoundedLru<String, String>(maxBytes = 6, sizeOf = { it.length })
        cache.put("a", "aaa")
        cache.put("b", "bbb")
        assertEquals(6, cache.sizeBytes)

        cache.put("c", "ccc")

        // 三项合计 9 字节超出 6：写入 c 时挤掉最早写入且此后未被读过的 a
        assertNull(cache.get("a"))
        assertSame("bbb", cache.get("b"))
        assertSame("ccc", cache.get("c"))
        assertEquals(6, cache.sizeBytes)
    }

    @Test
    fun `读取刷新淘汰顺序`() {
        val cache = SizeBoundedLru<String, String>(maxBytes = 4, sizeOf = { it.length })
        cache.put("a", "aa")
        cache.put("b", "bb")
        // a 刚被读过，淘汰顺序里排到 b 之后
        assertSame("aa", cache.get("a"))

        cache.put("c", "cc")

        assertSame("aa", cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `同键覆盖按新值重算占用`() {
        val cache = SizeBoundedLru<String, String>(maxBytes = 10, sizeOf = { it.length })
        cache.put("a", "aa")
        cache.put("a", "aaaa")

        assertSame("aaaa", cache.get("a"))
        assertEquals(4, cache.sizeBytes)
    }

    @Test
    fun `单张超过总容量不驻留`() {
        val cache = SizeBoundedLru<String, String>(maxBytes = 4, sizeOf = { it.length })
        cache.put("a", "aaaaa")

        // 收下它只会把其余全部挤掉，下一次还得重取，不如不驻留
        assertNull(cache.get("a"))
        assertEquals(0, cache.sizeBytes)
    }

    @Test
    fun `清空后容量归零`() {
        val cache = SizeBoundedLru<String, String>(maxBytes = 10, sizeOf = { it.length })
        cache.put("a", "aa")
        cache.clear()

        assertNull(cache.get("a"))
        assertEquals(0, cache.sizeBytes)
    }
}
