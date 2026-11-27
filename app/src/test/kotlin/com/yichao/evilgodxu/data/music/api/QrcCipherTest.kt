package com.yichao.evilgodxu.data.music.api

import org.junit.Assert.assertNull
import org.junit.Test

/** 只锁非法输入返回 null 的契约 */
class QrcCipherTest {

    @Test
    fun `空与非法十六进制被拒绝`() {
        assertNull(QrcCipher.decrypt(""))
        assertNull(QrcCipher.decrypt("   "))
        // 奇数长度无法两两成字节
        assertNull(QrcCipher.decrypt("abc"))
        // 非十六进制字符
        assertNull(QrcCipher.decrypt("zzzz"))
    }

    @Test
    fun `不足一块的密文被拒绝`() {
        assertNull(QrcCipher.decrypt("0011223344"))
    }

    @Test
    fun `无法解密的密文被拒绝`() {
        // 非 zlib 流解压失败返回 null
        assertNull(QrcCipher.decrypt("0000000000000000"))
        assertNull(QrcCipher.decrypt("ffffffffffffffff"))
    }
}
