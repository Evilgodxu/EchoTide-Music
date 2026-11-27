package com.yichao.evilgodxu.data.music.analysis

// 容器扩展名定不出码流，如 .m4a
private val CONTAINER_EXTENSIONS = setOf(
    "M4A", "M4B", "M4P", "M4R", "MP4", "OGG", "OGA", "MKA", "WEBM", "3GP",
)

// MPEG 扩展名与展示名不一致，.mpeg 即 MP3
private const val MPEG_EXTENSION = "MPEG"
private const val MP3_NAME = "MP3"

/** 扩展名能否单独定出格式名 */
internal fun extensionNamesFormat(path: String?): Boolean =
    path.extensionName()?.let { it !in CONTAINER_EXTENSIONS } == true

/** 展示格式名，容器名不可当格式名 */
internal fun formatDisplayName(path: String?, mime: String?): String? {
    val extension = path.extensionName()
    if (extension != null && extension !in CONTAINER_EXTENSIONS) {
        return if (extension == MPEG_EXTENSION) MP3_NAME else extension
    }
    return TrackAudioInfoReader.mimeToFormatName(mime) ?: extension
}

private fun String?.extensionName(): String? =
    this?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotBlank() }
