package com.yichao.evilgodxu.data.music.api

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * QRC 解密的输入校验复核。
 *
 * 该算法只提供解密方向，无法在测试内构造密文，故此处只锁住「数据非法时返回 null、
 * 由调用方按平台容忍缺失」这条契约；真实密文的解密正确性需以线上样本回归验证。
 */
class QrcCipherTest {

    @Test
    fun emptyAndMalformedHexAreRejected() {
        assertNull(QrcCipher.decrypt(""))
        assertNull(QrcCipher.decrypt("   "))
        // 奇数长度无法两两成字节
        assertNull(QrcCipher.decrypt("abc"))
        // 非十六进制字符
        assertNull(QrcCipher.decrypt("zzzz"))
    }

    @Test
    fun payloadShorterThanOneBlockIsRejected() {
        assertNull(QrcCipher.decrypt("0011223344"))
    }

    @Test
    fun undecryptablePayloadIsRejected() {
        // 长度合规但内容不是 zlib 流：解密后必然无法解压，返回 null 而非抛异常
        assertNull(QrcCipher.decrypt("0000000000000000"))
        assertNull(QrcCipher.decrypt("ffffffffffffffff"))
    }
}
