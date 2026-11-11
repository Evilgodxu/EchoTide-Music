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

/**
 * 远程封面取图入口：服务「按地址显示一张小图」——在线搜索候选封面与本机相册图片。
 *
 * 本地曲目封面不走这里：那条链路要按曲目决定来源（系统略缩图 / 音频内嵌原图），
 * 由 MusicCoverLoader 与它背后的两级缓存持有。
 *
 * 结果只驻留内存，不落盘：候选封面是一次性展示物，需要跨进程留存的只有当前/上一/下一三张大封面
 * （见 LargeCoverStore）。地址既可以是 http(s)，也可以是相册的 content URI，两者在本入口归一成字节流。
 */
internal object RemoteCoverLoader {

    // 容量口径：一次 96dp 的候选封面在 3x 屏上约 324 KB，8 MB 够放二十余张，
    // 也远小于堆里留给播放的那部分；不按堆比例划分——图片类常驻不该跟着堆一起放大
    // （同一取舍见 SystemThumbnailCache 的驻留上限）
    private const val MEMORY_CACHE_BYTES = 8 * 1024 * 1024

    private val cache = SizeBoundedLru<String, Bitmap>(
        maxBytes = MEMORY_CACHE_BYTES,
        sizeOf = { it.byteCount },
    )

    // 同键并发只取一次图：首帧未命中与随后触发的异步加载、同一候选在多个对话框同时出现，
    // 都会对同一地址发起请求；不合并就是重复下载与重复解码，而结果只有一份
    private val lock = Mutex()
    private val inflight = mutableMapOf<String, Deferred<Bitmap?>>()
    // 在飞取图不随任一调用方取消：同一结果通常还被另一个等待者共享
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 内存缓存命中查询：返回该地址在这一档尺寸上已驻留的图，未命中返回 null。
     *
     * 供显示端在组合期同步调用，使首帧直接落图而不是先闪一次占位块。
     * 本函数不上锁也不触发取图。
     */
    fun peek(url: String, targetPx: Int): Bitmap? = cache.get(cacheKey(url, targetPx))

    /** 取图并按最长边收口到 [targetPx]；地址取不到、内容不是图片或解码失败时返回 null */
    suspend fun load(context: Context, url: String, targetPx: Int): Bitmap? {
        val key = cacheKey(url, targetPx)
        cache.get(key)?.let { return it }
        // 取图本身可能比发起方活得更久（结果还被别人等着），故只在应用上下文上执行
        val appContext = context.applicationContext
        val (pending, owner) = lock.withLock {
            inflight[key]?.let { return@withLock it to false }
            scope.async { fetch(appContext, url, targetPx)?.also { cache.put(key, it) } }
                .also { inflight[key] = it } to true
        }
        return try {
            pending.await()
        } catch (e: CancellationException) {
            // 发起方被取消时撤掉在飞取图，避免条目永久滞留在表中；仅等待的调用方直接退出等待
            if (owner) pending.cancel()
            throw e
        } finally {
            // 取消路径同样要清理，故以 NonCancellable 保证清理动作不被已取消的作业拦下
            if (owner) withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === pending) inflight.remove(key) }
            }
        }
    }

    // 档位进键：同一张图在不同控件尺寸下各自驻留，避免把 28dp 的行图放大给 96dp 的候选预览
    private fun cacheKey(url: String, targetPx: Int): String = "$targetPx|$url"

    // 失败一律落成 null：候选封面取不到就是不出图，不该把异常抛回组合期的协程
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
            // 在线封面本身就是小图，一次读全再解两遍比重新发请求便宜
            val bytes = readHttpBytes(url) ?: return null
            decodeFitted(targetPx) { ByteArrayInputStream(bytes) }
        } else {
            // 相册原图可能上千万像素，整读进堆只为算尺寸不划算；两遍解码各开一次流
            decodeFitted(targetPx) { context.contentResolver.openInputStream(url.toUri()) }
        }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun readHttpBytes(url: String): ByteArray? =
        // 与在线音源共用连接池：封面请求没必要另起一套 OkHttp
        MusicHttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes()
        }

    // 两段式解码：先只读尺寸算出采样率，再按采样率真正解码。
    // 直接整幅解码会让一张 1000×1000 的候选封面先占 4 MB，再缩到不足 100px
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

    // 采样率只按 2 的幂折半，解出的图仍可能大于目标尺寸，这里再精确收口一次；
    // 缩放失败（如内存不足）时退回采样结果，不因收口失败丢掉整张图
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
        // 缩放产生的是新位图，采样结果在本入口内不再被引用，立即释放以免大图滞留到 GC
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}

/**
 * 解码采样率：以 2 的幂逐级折半，直到最长边不再大于目标边长的两倍。
 *
 * 单靠采样会让驻留尺寸最多达到目标的 4 倍，故调用方还要按目标边长精确缩放一次（见 fitToTarget）。
 * 抽成纯函数以便单测覆盖边界。
 */
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
