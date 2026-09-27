package com.yichao.evilgodxu.data.music.metadata

import android.content.Context

// 本地音频内嵌文本标签读取：取 WAV(RIFF)、AIFF/AIFC、DSDIFF、DSF、APE 的标题/艺术家/专辑。
// 这些容器的标签平台元数据提取器读不到——WAV 的容器内 "ID3 " 块与 LIST/INFO、AIFF/DSDIFF 的 "ID3 " 块、
// DSF 的尾部标签、APE 的 APEv2 条目都不在平台支持范围内，MediaStore 只能给出文件名派生的标题，
// 故曲目入库时改由本读取器从文件自身补齐。
// 平台已能读标签的容器（MP3/FLAC/M4A/Ogg）不在读取范围内，避免为全库逐曲多读一次文件。
// 标签位于音频之后的容器需要尾窗定位，尾窗之外的标签由 readAt 按绝对偏移定点读取。
internal object MusicEmbeddedTagReader {

    // 容器类型探测前缀长度：仅需覆盖各容器的魔数
    private const val CONTAINER_PROBE_BYTES = 16
    // 自实现解析的头部窗口：容器头与块表都在文件头部，文本标签所需的块表远小于此
    private const val HEADER_CAP = 64 * 1024
    // 尾部窗口：标签紧贴文件末尾（WAV/DSF/APE）。窗口只负责给出块头，
    // 块头与块体落在窗口外时由 readAt 按绝对偏移定点读取，故无需为整段标签预留窗口
    private const val TAIL_CAP = 512 * 1024

    /**
     * 读取文件内嵌的文本标签；容器不在自解析范围内或无内嵌标签时返回 null。
     * 调用方须在 IO 线程调用：本方法按窗口读取文件字节，不做线程切换
     */
    fun read(context: Context, path: String, audioUri: String): LosslessContainerTags.TextTag? {
        // 先读极短前缀判定容器，非自解析容器就此返回，不为每首曲目都去解析标签
        val prefix = LocalAudioSource.read(context, path, audioUri, 0L, CONTAINER_PROBE_BYTES) ?: return null
        if (!LosslessContainerTags.matches(prefix)) return null
        val header = LocalAudioSource.read(context, path, audioUri, 0L, HEADER_CAP) ?: return null
        val tail = LocalAudioSource.tail(context, path, audioUri, TAIL_CAP)
        return LosslessContainerTags.readText(header, tail?.first, tail?.second ?: 0L) { offset, count ->
            LocalAudioSource.read(context, path, audioUri, offset, count)
        }
    }
}
