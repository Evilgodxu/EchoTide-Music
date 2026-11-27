package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// 内嵌封面读取：路径优先，解码对齐系统略缩图
internal object EmbeddedCoverReader {

    // 容器探测前缀 16 字节
    private const val CONTAINER_PROBE_BYTES = 16
    // 头部窗口，RIFF 块表在头部
    private const val HEADER_CAP = 512 * 1024
    // 尾部窗口，WAV 标签紧贴末尾
    private const val TAIL_CAP = 2 * 1024 * 1024

    // 三态：Found / 无封面 / 读不出，后者值得重试
    sealed interface Result {
        data class Found(val bitmap: Bitmap) : Result
        data object Absent : Result
        data object Unavailable : Result
    }

    /** 读内嵌封面并按 sizePx 解码；无封面或失败不含位图 */
    suspend fun read(context: Context, audioUri: String, path: String, sizePx: Int): Result =
        withContext(Dispatchers.IO) {
            when (val picture = readPicture(context, audioUri, path)) {
                is Picture.Found ->
                    // 字节已取到却解不出，说明数据损坏，不再重试
                    decode(picture.bytes, sizePx)?.let { Result.Found(it) } ?: Result.Absent
                Picture.Absent -> Result.Absent
                Picture.Unavailable -> Result.Unavailable
            }
        }

    /** 读内嵌封面原图，失败 null */
    suspend fun readRawBytes(context: Context, audioUri: String, path: String): ByteArray? =
        withContext(Dispatchers.IO) { (readPicture(context, audioUri, path) as? Picture.Found)?.bytes }

    /** 读内嵌原图并按长边上限解码，中间留取消检查点 */
    suspend fun readFitted(context: Context, audioUri: String, path: String, maxEdgePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val picture = readPicture(context, audioUri, path) as? Picture.Found ?: return@withContext null
            ensureActive()
            decodeFitted(picture.bytes, maxEdgePx)
        }

    // 未解码的内嵌图片，三态同 Result
    private sealed interface Picture {
        class Found(val bytes: ByteArray) : Picture
        data object Absent : Picture
        data object Unavailable : Picture
    }

    private fun readPicture(context: Context, audioUri: String, path: String): Picture {
        val prefix = LocalAudioSource.read(context, path, audioUri, 0L, CONTAINER_PROBE_BYTES)
        // 平台读不到标签的容器先走自实现解析
        if (prefix != null && isCoverFallbackContainer(prefix)) {
            return readPictureByContainerTags(context, audioUri, path, prefix)
                ?: readPictureByRetriever(context, audioUri, path)
        }
        val retrieved = readPictureByRetriever(context, audioUri, path)
        if (retrieved is Picture.Found) return retrieved
        return prefix?.let { readPictureByContainerTags(context, audioUri, path, it) } ?: retrieved
    }

    private fun readPictureByRetriever(context: Context, audioUri: String, path: String): Picture {
        if (path.isNotBlank()) {
            when (val picture = pictureFromPath(path)) {
                is Picture.Found -> return picture
                // 已读出且无图，换 URI 结果不变，不再重复打开
                Picture.Absent -> return picture
                Picture.Unavailable -> Unit
            }
        }
        val uri = runCatching { audioUri.toUri() }.getOrNull() ?: return Picture.Unavailable
        // 在线流无内嵌图，属「本就没有」
        if (uri.scheme != "content" && uri.scheme != "file") return Picture.Absent
        return pictureFromUri(context, uri)
    }

    // 自实现容器标签取图；非自解析容器返回 null
    private fun readPictureByContainerTags(
        context: Context,
        audioUri: String,
        path: String,
        prefix: ByteArray,
    ): Picture? {
        if (!isCoverFallbackContainer(prefix)) return null
        val header = LocalAudioSource.read(context, path, audioUri, 0L, HEADER_CAP) ?: return null
        val tail = LocalAudioSource.tail(context, path, audioUri, TAIL_CAP)
        val bytes = LosslessContainerTags.readCover(header, tail?.first, tail?.second ?: 0L) { offset, count ->
            LocalAudioSource.read(context, path, audioUri, offset, count)
        }
        return bytes?.let { Picture.Found(it) }
    }

    // 是否走自实现解析：RIFF/WAVE
    private fun isCoverFallbackContainer(prefix: ByteArray): Boolean =
        LosslessContainerTags.matches(prefix)

    private fun pictureFromPath(path: String): Picture = withRetriever(path) { it.setDataSource(path) }

    private fun pictureFromUri(context: Context, uri: Uri): Picture =
        withRetriever(uri.toString()) { it.setDataSource(context, uri) }

    private fun withRetriever(target: String, open: (MediaMetadataRetriever) -> Unit): Picture {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
            retriever.embeddedPicture?.let { Picture.Found(it) } ?: Picture.Absent
        } catch (e: Exception) {
            CrashLogManager.logException("EmbeddedCoverReader", "读取内嵌封面失败: $target", e)
            Picture.Unavailable
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CrashLogManager.logException("EmbeddedCoverReader", "释放元数据读取器失败", e)
            }
        }
    }

    // 解码参数与系统略缩图一致，输出可能大于请求尺寸
    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? = runCatching {
        val request = sizePx.coerceAtLeast(1)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = maxOf(info.size.width / request, info.size.height / request)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }.getOrNull()

    // 大封面解码：超上限才按精确尺寸缩放
    private fun decodeFitted(bytes: ByteArray, maxEdgePx: Int): Bitmap? = runCatching {
        val limit = maxEdgePx.coerceAtLeast(1)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longest = maxOf(width, height)
            if (longest > limit) {
                val scale = limit.toFloat() / longest
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrNull()
}
