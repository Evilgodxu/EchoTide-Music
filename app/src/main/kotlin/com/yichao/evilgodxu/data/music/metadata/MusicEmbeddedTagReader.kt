package com.yichao.evilgodxu.data.music.metadata

import android.content.Context

// WAV 内嵌文本标签；平台读不到由此补齐
internal object MusicEmbeddedTagReader {

    // 容器探测前缀 16 字节
    private const val CONTAINER_PROBE_BYTES = 16
    // 头部窗口；块表在头部，远小于此
    private const val HEADER_CAP = 64 * 1024
    // 尾部窗口；窗口外标签由 readAt 定点读
    private const val TAIL_CAP = 512 * 1024

    /** 读内嵌文本标签；无标签返回 null，须在 IO 线程 */
    fun read(context: Context, path: String, audioUri: String): LosslessContainerTags.TextTag? {
        // 先读前缀判容器，非自解析容器直接返回
        val prefix = LocalAudioSource.read(context, path, audioUri, 0L, CONTAINER_PROBE_BYTES) ?: return null
        if (!LosslessContainerTags.matches(prefix)) return null
        val header = LocalAudioSource.read(context, path, audioUri, 0L, HEADER_CAP) ?: return null
        val tail = LocalAudioSource.tail(context, path, audioUri, TAIL_CAP)
        return LosslessContainerTags.readText(header, tail?.first, tail?.second ?: 0L) { offset, count ->
            LocalAudioSource.read(context, path, audioUri, offset, count)
        }
    }
}
