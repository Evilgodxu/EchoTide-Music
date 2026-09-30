package com.yichao.evilgodxu.data.music.api

import android.annotation.SuppressLint
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object NeteaseCrypto {
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val RSA_PUBLIC_KEY = "010001"
    private const val RSA_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val EAPI_AES_KEY = "e82ckenh8dichen8"
    private const val EAPI_MARK = "-36cd479b6b5-"
    private val random = SecureRandom()
    private val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun weapi(json: String): Map<String, String> {
        val secret = buildString { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }
        return mapOf(
            "params" to aes(json, PRESET_KEY).let { aes(it, secret) },
            "encSecKey" to rsa(secret)
        )
    }

    // EAPI 参数加密：拼接 path/body/digest 后 AES-128-ECB 加密，输出 hex。
    // ECB 由服务端协议固定：换用 CBC/GCM 等模式会导致接口拒绝请求，故此处不适用
    // 「ECB 不应使用」的通则——本处加密的是接口凭据，不是需要语义安全的用户数据
    @SuppressLint("GetInstance")
    fun eapi(path: String, body: String): String {
        val params = "${path}${EAPI_MARK}${body}${EAPI_MARK}${md5Hex("nobody${path}use${body}md5forencrypt")}"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_AES_KEY.toByteArray(), "AES"))
        return cipher.doFinal(params.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun md5Hex(value: String): String {
        return MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun aes(value: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(IV.toByteArray()))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun rsa(secret: String): String {
        val reversed = secret.reversed().toByteArray(StandardCharsets.UTF_8)
        val result = BigInteger(1, reversed).modPow(
            BigInteger(RSA_PUBLIC_KEY, 16),
            BigInteger(RSA_MODULUS, 16)
        ).toString(16)
        return result.padStart(256, '0')
    }
}
