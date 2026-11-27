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

// 单批翻译结果，区分可重试、规模过大与成功
internal sealed interface TranslateResult {
    // 成功，texts 与提交文本等长同序
    data class Success(val texts: List<String>) : TranslateResult

    // 规模超限（约 1000 字符），缩小批次可能成功
    data object TooLarge : TranslateResult

    // 限流或网络异常，已按退避重试仍失败
    data object Unavailable : TranslateResult
}

// 文本翻译走有道免鉴权接口，无需密钥
// 单次约 1000 字符，超限 103；限流 411
// 批量以换行拼接提交，还原行数不符即失败，避免译文错位
internal object TranslationApi {

    const val TARGET_CHINESE = "zh-CHS"

    const val MAX_BATCH_SIZE = 20

    // 官方演示接口，仅接受短文本
    private const val ENDPOINT = "https://aidemo.youdao.com/trans"

    // 文本总量超限的错误码
    private const val CODE_TOO_LARGE = "103"

    // 命中限流后的退避间隔（毫秒），逐级加长
    private val RETRY_BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L)

    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

    // 限流退避重试，规模超限交给调用方拆分
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

    // 译文按行还原，行数不符视为错位
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
