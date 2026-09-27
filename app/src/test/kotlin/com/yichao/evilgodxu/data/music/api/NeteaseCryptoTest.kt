package com.yichao.evilgodxu.data.music.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网易云参数加密复核。
 *
 * EAPI 用固定密钥，输出是可预测的，故按公开算法解密回原文逐段比对；
 * WEAPI 的第二次加密用随机密钥，无法解密回原文，只能校验密文结构与密钥长度。
 */
class NeteaseCryptoTest {

    private companion object {
        // 与实现同源的公开常量：EAPI 的固定密钥、分隔符与 RSA 模数长度
        const val EAPI_KEY = "e82ckenh8dichen8"
        const val MARK = "-36cd479b6b5-"
    }

    @Test
    fun eapiCarriesPathBodyAndDigest() {
        val path = "/api/song/enhance/player/url"
        val body = "{\"ids\":\"[1]\"}"
        val plain = decryptEapi(NeteaseCrypto.eapi(path, body))
        assertEquals("$path$MARK$body$MARK" + md5Hex("nobody${path}use${body}md5forencrypt"), plain)
    }

    @Test
    fun eapiOutputIsLowercaseHexOfWholeBlocks() {
        val hex = NeteaseCrypto.eapi("/api/v1/playlist", "{}")
        // AES-ECB 输出按 16 字节分块，十六进制串长度必为 32 的倍数
        assertEquals(0, hex.length % 32)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun eapiIsDeterministic() {
        assertEquals(NeteaseCrypto.eapi("/p", "b"), NeteaseCrypto.eapi("/p", "b"))
        assertNotEquals(NeteaseCrypto.eapi("/p", "b"), NeteaseCrypto.eapi("/p", "c"))
    }

    @Test
    fun weapiEncryptsParamsIntoWholeBlocks() {
        val result = NeteaseCrypto.weapi("{\"id\":1}")
        val params = Base64.getDecoder().decode(result.getValue("params"))
        // 两次 AES-CBC 叠加后仍是 16 字节的整数倍
        assertEquals(0, params.size % 16)
        assertEquals(setOf("params", "encSecKey"), result.keys)
    }

    @Test
    fun weapiSecretKeyIsEncodedIn256HexDigits() {
        // 服务端按 RSA 模数的位数（1024 bit）解析该字段，长度不足即被拒
        val key = NeteaseCrypto.weapi("{}").getValue("encSecKey")
        assertEquals(256, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun weapiUsesAFreshSecretPerCall() {
        // 随机密钥每次不同，密文随之不同，否则同一请求可被重放比对
        assertNotEquals(NeteaseCrypto.weapi("{}")["params"], NeteaseCrypto.weapi("{}")["params"])
    }

    // -----------------------------------------------------------------------

    private fun decryptEapi(hex: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(StandardCharsets.UTF_8), "AES"))
        return String(cipher.doFinal(hexToBytes(hex)), StandardCharsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        ((Character.digit(hex[index * 2], 16) shl 4) or Character.digit(hex[index * 2 + 1], 16)).toByte()
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
