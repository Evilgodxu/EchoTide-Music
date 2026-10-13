package com.yichao.evilgodxu.data.music.analysis

// 只说得出容器的扩展名：这类扩展名给不出码流——.m4a 内既可能是 AAC 也可能是 ALAC，
// .ogg 内可能是 Vorbis 也可能是 Opus——故格式名须另找来源（mime 或容器头）
private val CONTAINER_EXTENSIONS = setOf(
    "M4A", "M4B", "M4P", "M4R", "MP4", "CAF", "OGG", "OGA", "MKA", "WEBM", "3GP",
)

// MPEG 音频的扩展名与展示名不一致（.mpeg 文件即 MP3）
private const val MPEG_EXTENSION = "MPEG"
private const val MP3_NAME = "MP3"

/**
 * 扩展名能否单独定出格式名。
 *
 * 容器扩展名与无扩展名都定不出（返回 false），此时调用方须另行取 mime 或容器头。本判据只用来决定
 * 「要不要额外读一次 mime」——读一次要开一次 MediaMetadataRetriever，非容器扩展名不必付这个代价。
 */
internal fun extensionNamesFormat(path: String?): Boolean =
    path.extensionName()?.let { it !in CONTAINER_EXTENSIONS } == true

/**
 * 展示用的格式名：扩展名能定案时取扩展名，否则取 mime，两者都定不出时回落到容器名。
 *
 * 容器扩展名不能当格式名，是这条规则的由来：`.m4a` 里的码流可能是 AAC 也可能是 ALAC，
 * 把容器名当格式名展示会让「有无损失」与「无损升级提示」跟着一起错
 * （[isLosslessFormatName] 认不出 `M4A`，一首无损的 ALAC 因此会被判成有损而被提示做无损升级）。
 * 码流的直接来源是 mime 与容器头，后者更直接（见 [ContainerFormat.codecName]）；调用方手上
 * 若有容器头的认定结论，应先于本函数取用。
 */
internal fun formatDisplayName(path: String?, mime: String?): String? {
    val extension = path.extensionName()
    if (extension != null && extension !in CONTAINER_EXTENSIONS) {
        return if (extension == MPEG_EXTENSION) MP3_NAME else extension
    }
    return TrackAudioInfoReader.mimeToFormatName(mime) ?: extension
}

private fun String?.extensionName(): String? =
    this?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotBlank() }
