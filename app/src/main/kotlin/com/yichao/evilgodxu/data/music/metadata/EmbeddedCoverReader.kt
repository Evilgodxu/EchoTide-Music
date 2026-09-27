package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 内嵌封面读取：从音频文件自身取出内嵌图片，本地文件路径优先、其次 content/file URI；
// 纯在线流没有本地文件，其内嵌图不可取（缓存落盘后即转为可取的本地源）。
// 解码行为对齐系统生成音频略缩图的路径，使非索引曲目的封面观感与系统略缩图一致。
// 读取器不持有缓存：重复读取的去重与驻留由 EmbeddedCoverCache 负责。
internal object EmbeddedCoverReader {

    // 容器类型探测前缀长度：仅需覆盖各容器的魔数
    private const val CONTAINER_PROBE_BYTES = 16
    // 自实现解析的头部窗口：IFF 块表与 DSF 头都在文件头部
    private const val HEADER_CAP = 512 * 1024
    // 尾部窗口：WAV/DSF/APE 的标签紧贴文件末尾
    private const val TAIL_CAP = 2 * 1024 * 1024

    // 读取结果三态。必须区分「文件读不出」与「文件正常但没有内嵌封面」：
    // 前者才值得换另一条取数路径重试；后者读的是同一文件的同一段标签，重试结果必然相同
    sealed interface Result {
        data class Found(val bitmap: Bitmap) : Result
        data object Absent : Result
        data object Unavailable : Result
    }

    /** 读取曲目的内嵌封面并按最长边 [sizePx] 请求解码；无内嵌封面、图片损坏或文件读不出时不含位图 */
    suspend fun read(context: Context, audioUri: String, path: String, sizePx: Int): Result =
        withContext(Dispatchers.IO) {
            when (val picture = readPicture(context, audioUri, path)) {
                is Picture.Found ->
                    // 字节已取到却解不出图，说明这段数据自身损坏；换取数路径读到的仍是同一段字节，不再重试
                    decode(picture.bytes, sizePx)?.let { Result.Found(it) } ?: Result.Absent
                Picture.Absent -> Result.Absent
                Picture.Unavailable -> Result.Unavailable
            }
        }

    /** 读取内嵌封面原图字节（不重编码）；无内嵌封面或读取失败时返回 null */
    suspend fun readRawBytes(context: Context, audioUri: String, path: String): ByteArray? =
        withContext(Dispatchers.IO) { (readPicture(context, audioUri, path) as? Picture.Found)?.bytes }

    // 未解码的原始内嵌图片，三态语义与 Result 一一对应
    private sealed interface Picture {
        class Found(val bytes: ByteArray) : Picture
        data object Absent : Picture
        data object Unavailable : Picture
    }

    private fun readPicture(context: Context, audioUri: String, path: String): Picture {
        val retrieved = readPictureByRetriever(context, audioUri, path)
        if (retrieved is Picture.Found) return retrieved
        // 平台提取器读不出或读到了却没有内嵌图时，回落到自实现的容器标签解析：
        // AIFF/APE/DSF/DFF 无平台元数据支持，WAV 的容器内 ID3 块平台也不读取，
        // 这些容器的内嵌封面只能由本地解析取得
        return readPictureByContainerTags(context, audioUri, path) ?: retrieved
    }

    private fun readPictureByRetriever(context: Context, audioUri: String, path: String): Picture {
        if (path.isNotBlank()) {
            when (val picture = pictureFromPath(path)) {
                is Picture.Found -> return picture
                // 同一文件已能正常读出且无内嵌图片，换 content URI 再读结果不变，不再重复打开
                Picture.Absent -> return picture
                Picture.Unavailable -> Unit
            }
        }
        val uri = runCatching { Uri.parse(audioUri) }.getOrNull() ?: return Picture.Unavailable
        // 非本地协议（在线 http 流）无内嵌图片可取，属「本就没有」而非「读不出」
        if (uri.scheme != "content" && uri.scheme != "file") return Picture.Absent
        return pictureFromUri(context, uri)
    }

    // 自实现的容器标签取图。先读入极短前缀判定容器类型：平台提取器已覆盖的 MP3/FLAC/M4A/Ogg
    // 直接返回 null，避免为每首无封面的曲目都去解析标签
    private fun readPictureByContainerTags(context: Context, audioUri: String, path: String): Picture? {
        val prefix = LocalAudioSource.read(context, path, audioUri, 0L, CONTAINER_PROBE_BYTES) ?: return null
        if (!isCoverFallbackContainer(prefix)) return null
        val header = LocalAudioSource.read(context, path, audioUri, 0L, HEADER_CAP) ?: return null
        val tail = LocalAudioSource.tail(context, path, audioUri, TAIL_CAP)
        val bytes = LosslessContainerTags.readCover(header, tail?.first, tail?.second ?: 0L) { offset, count ->
            LocalAudioSource.read(context, path, audioUri, offset, count)
        }
        return bytes?.let { Picture.Found(it) }
    }

    // 是否值得走自实现解析：AIFF/AIFC、DSDIFF、DSF、APE 以及 RIFF/WAVE
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

    // 解码参数与系统音频略缩图保持一致：软件位图 + 整数级降采样（max(宽/请求, 高/请求) 向下取整，
    // sample 不大于 1 时不设），保持宽高比、不裁剪、不改色彩空间。
    // 因此输出可能大于请求尺寸（如 1000px 源请求 512 时整数除法得 1，原尺寸输出），
    // 这是系统侧的既有行为，显示与系统略缩图的一致性依赖于此，不另行收口
    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? = runCatching {
        val request = sizePx.coerceAtLeast(1)
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = maxOf(info.size.width / request, info.size.height / request)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }.getOrNull()
}
