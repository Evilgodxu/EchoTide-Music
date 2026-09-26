package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.log.CrashLogManager
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** 单批翻译的结局：区分「可重试」「规模过大」与「成功」，调用方据此决定是否拆分重试 */
internal sealed interface TranslateResult {
    /** 成功，[texts] 与提交的文本等长且顺序一致 */
    data class Success(val texts: List<String>) : TranslateResult

    /** 提交规模超出接口限制（实测单次约 1000 字符），缩小批次后可能成功 */
    data object TooLarge : TranslateResult

    /** 限流或网络异常，已按退避重试仍失败 */
    data object Unavailable : TranslateResult
}

/**
 * 文本翻译：走有道公开的免鉴权接口（在线翻译的官方演示入口），不需要注册密钥。
 *
 * 该接口有两项硬约束，都在此处处理：
 * - 单次提交的文本总量约 1000 字符，超出返回错误码 103；
 * - 按 IP 限流，突发请求返回错误码 411。
 *
 * 批量文本以换行拼接提交、结果按行还原，还原行数与提交行数不一致即判定失败 ——
 * 宁可少补几行，也不能把译文错配到别的歌词行上。
 */
internal object TranslationApi {

    /** 目标语言：简体中文 */
    const val TARGET_CHINESE = "zh-CHS"

    /** 单次请求最多提交的文本条数上限 */
    const val MAX_BATCH_SIZE = 20

    // 官方演示接口，仅接受短文本
    private const val ENDPOINT = "https://aidemo.youdao.com/trans"

    // 文本总量超限的错误码
    private const val CODE_TOO_LARGE = "103"

    // 命中限流后的退避间隔（毫秒），逐级加长
    private val RETRY_BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L)

    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

    /** 翻译一批文本；限流按退避重试，规模超限立即返回 [TranslateResult.TooLarge] 交由调用方拆分 */
    suspend fun translate(texts: List<String>, targetLanguage: String = TARGET_CHINESE): TranslateResult {
        if (texts.isEmpty()) return TranslateResult.Success(emptyList())
        if (texts.size > MAX_BATCH_SIZE) return TranslateResult.TooLarge
        return withContext(Dispatchers.IO) {
            var attempt = 0
            var outcome = requestTranslation(texts, targetLanguage)
            while (outcome is TranslateResult.Unavailable && attempt < RETRY_BACKOFF_MS.size) {
                delay(RETRY_BACKOFF_MS[attempt])
                attempt++
                outcome = requestTranslation(texts, targetLanguage)
            }
            outcome
        }
    }

    private fun requestTranslation(texts: List<String>, targetLanguage: String): TranslateResult = try {
        val form = "q=${URLEncoder.encode(texts.joinToString("\n"), "UTF-8")}" +
            "&from=Auto&to=${URLEncoder.encode(targetLanguage, "UTF-8")}"
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(form.toRequestBody(FORM_MEDIA_TYPE))
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .build()
        MusicHttpClient.client.newCall(request).execute().use { response ->
            val body = response.body.string().orEmpty()
            if (response.isSuccessful) parseTranslations(body, texts.size) else TranslateResult.Unavailable
        }
    } catch (e: Exception) {
        CrashLogManager.logException("TranslationApi", "翻译请求失败: 条数=${texts.size}", e)
        TranslateResult.Unavailable
    }

    // 译文以「单个元素内含换行分隔」的数组返回，按行还原后必须与提交行数一致，否则视为错位
    private fun parseTranslations(body: String, expected: Int): TranslateResult {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return TranslateResult.Unavailable
        val code = root.optString("errorCode")
        if (code == CODE_TOO_LARGE) return TranslateResult.TooLarge
        if (code != "0") return TranslateResult.Unavailable
        val array = root.optJSONArray("translation") ?: return TranslateResult.Unavailable
        val joined = (0 until array.length()).joinToString("\n") { array.optString(it) }
        val lines = joined.split("\n")
        return if (lines.size == expected) {
            TranslateResult.Success(lines.map { it.trim() })
        } else {
            TranslateResult.Unavailable
        }
    }
}
