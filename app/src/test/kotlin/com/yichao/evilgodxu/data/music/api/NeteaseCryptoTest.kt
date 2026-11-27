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

/** EAPI 解密比对，WEAPI 只校验结构 */
class NeteaseCryptoTest {

    private companion object {
        // EAPI 固定密钥与分隔符
        const val EAPI_KEY = "e82ckenh8dichen8"
        const val MARK = "-36cd479b6b5-"
    }

    @Test
    fun `EAPI 携带路径、正文与摘要`() {
        val path = "/api/song/enhance/player/url"
        val body = "{\"ids\":\"[1]\"}"
        val plain = decryptEapi(NeteaseCrypto.eapi(path, body))
        assertEquals("$path$MARK$body$MARK" + md5Hex("nobody${path}use${body}md5forencrypt"), plain)
    }

    @Test
    fun `EAPI 输出为整块的十六进制小写`() {
        val hex = NeteaseCrypto.eapi("/api/v1/playlist", "{}")
        // AES-ECB 16 字节分块，长度 32 倍数
        assertEquals(0, hex.length % 32)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `EAPI 结果确定`() {
        assertEquals(NeteaseCrypto.eapi("/p", "b"), NeteaseCrypto.eapi("/p", "b"))
        assertNotEquals(NeteaseCrypto.eapi("/p", "b"), NeteaseCrypto.eapi("/p", "c"))
    }

    @Test
    fun `WEAPI 把参数加密成整块`() {
        val result = NeteaseCrypto.weapi("{\"id\":1}")
        val params = Base64.getDecoder().decode(result.getValue("params"))
        // 两次 AES-CBC 后为 16 字节倍数
        assertEquals(0, params.size % 16)
        assertEquals(setOf("params", "encSecKey"), result.keys)
    }

    @Test
    fun `WEAPI 密钥编码为 256 位十六进制`() {
        // 按 1024 bit 解析，不足被拒
        val key = NeteaseCrypto.weapi("{}").getValue("encSecKey")
        assertEquals(256, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `WEAPI 每次调用更换密钥`() {
        // 随机密钥每次不同，防重放比对
        assertNotEquals(NeteaseCrypto.weapi("{}")["params"], NeteaseCrypto.weapi("{}")["params"])
    }

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
