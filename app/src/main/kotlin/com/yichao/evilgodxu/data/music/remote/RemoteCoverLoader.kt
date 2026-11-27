package com.yichao.evilgodxu.data.music.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

// 远程封面取图入口，服务「按地址显示小图」
// 本地曲目封面走 MusicCoverLoader，不经过这里
// 结果只驻留内存，远程与相册地址归一为字节流
internal object RemoteCoverLoader {

    // 8MB 可放二十余张候选封面，不随堆比例放大
    private const val MEMORY_CACHE_BYTES = 8 * 1024 * 1024

    private val cache = SizeBoundedLru<String, Bitmap>(
        maxBytes = MEMORY_CACHE_BYTES,
        sizeOf = { it.byteCount },
    )

    // 同键并发只取一次图，避免重复下载与解码
    private val lock = Mutex()
    private val inflight = mutableMapOf<String, Deferred<Bitmap?>>()
    // 在飞取图不随调用方取消，结果仍被他人共享
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 内存缓存命中查询，未命中返回 null
    // 供组合期同步调用，使首帧直接落图；不上锁不取图
    fun peek(url: String, targetPx: Int): Bitmap? = cache.get(cacheKey(url, targetPx))

    // 取图并按最长边收口到 targetPx，失败返回 null
    suspend fun load(context: Context, url: String, targetPx: Int): Bitmap? {
        val key = cacheKey(url, targetPx)
        cache.get(key)?.let { return it }
        // 取图可能比发起方活得更久，只用应用上下文
        val appContext = context.applicationContext
        val (pending, owner) = lock.withLock {
            inflight[key]?.let { return@withLock it to false }
            scope.async { fetch(appContext, url, targetPx)?.also { cache.put(key, it) } }
                .also { inflight[key] = it } to true
        }
        return try {
            pending.await()
        } catch (e: CancellationException) {
            // 发起方取消时撤掉在飞取图，避免条目滞留
            if (owner) pending.cancel()
            throw e
        } finally {
            // 取消路径也要清理，用 NonCancellable 保证执行
            if (owner) withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === pending) inflight.remove(key) }
            }
        }
    }

    // 档位进键，不同尺寸各自驻留避免放大模糊
    private fun cacheKey(url: String, targetPx: Int): String = "$targetPx|$url"

    // 失败一律落 null，不把异常抛回组合期协程
    private suspend fun fetch(context: Context, url: String, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                decodeRemote(context, url, targetPx)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private fun decodeRemote(context: Context, url: String, targetPx: Int): Bitmap? =
        if (isHttpUrl(url)) {
            // 在线封面是小图，一次读全再解两遍
            val bytes = readHttpBytes(url) ?: return null
            decodeFitted(targetPx) { ByteArrayInputStream(bytes) }
        } else {
            // 相册原图可能极大，两遍解码各开一次流
            decodeFitted(targetPx) { context.contentResolver.openInputStream(url.toUri()) }
        }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun readHttpBytes(url: String): ByteArray? =
        // 与在线音源共用连接池
        MusicHttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes()
        }

    // 两段式解码：先读尺寸算采样率，再真正解码
    // 避免整幅解码先占数 MB 再缩小
    private fun decodeFitted(targetPx: Int, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = remoteCoverSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return fitToTarget(decoded, targetPx)
    }

    // 采样率按 2 的幂折半，再精确收口一次
    // 缩放失败则退回采样结果，不丢整图
    private fun fitToTarget(bitmap: Bitmap, targetPx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= targetPx) return bitmap
        val scale = targetPx.toFloat() / longest
        val scaled = runCatching {
            bitmap.scale(
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                filter = true,
            )
        }.getOrNull() ?: return bitmap
        // 缩放产生新位图，旧图立即释放以免滞留
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}

// 解码采样率：2 的幂折半至最长边不超过目标两倍
// 纯函数便于单测边界
internal fun remoteCoverSampleSize(sourceWidth: Int, sourceHeight: Int, targetPx: Int): Int {
    if (targetPx <= 0) return 1
    var sample = 1
    var longest = max(sourceWidth, sourceHeight)
    while (longest / 2 >= targetPx) {
        longest /= 2
        sample *= 2
    }
    return sample
}
