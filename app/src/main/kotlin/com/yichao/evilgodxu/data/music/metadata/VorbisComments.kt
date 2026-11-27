package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream

// Vorbis 注释键，FLAC 与 Ogg 共用
internal val VORBIS_KEYS: Map<TagField, String> = mapOf(
    TagField.TITLE to "TITLE",
    TagField.ARTIST to "ARTIST",
    TagField.ALBUM_ARTIST to "ALBUMARTIST",
    TagField.ALBUM to "ALBUM",
    TagField.DATE to "DATE",
    TagField.LANGUAGE to "LANGUAGE",
    TagField.GENRE to "GENRE",
    TagField.TRACK_NUMBER to "TRACKNUMBER",
    TagField.DISC_NUMBER to "DISCNUMBER",
    TagField.LYRICIST to "LYRICIST",
    TagField.COMPOSER to "COMPOSER",
    TagField.COPYRIGHT to "COPYRIGHT",
    TagField.COMMENT to "COMMENT",
    TagField.RATING to "RATING",
    TagField.REPLAY_GAIN_TRACK_GAIN to "REPLAYGAIN_TRACK_GAIN",
    TagField.REPLAY_GAIN_TRACK_PEAK to "REPLAYGAIN_TRACK_PEAK",
    TagField.REPLAY_GAIN_ALBUM_GAIN to "REPLAYGAIN_ALBUM_GAIN",
    TagField.REPLAY_GAIN_ALBUM_PEAK to "REPLAYGAIN_ALBUM_PEAK",
    TagField.REPLAY_GAIN_REFERENCE_LOUDNESS to "REPLAYGAIN_REFERENCE_LOUDNESS",
    TagField.LYRICS to "LYRICS",
)

// FLAC 图片块与 Ogg 的 PICTURE 同版式
internal fun pictureBlock(cover: ByteArray): ByteArray {
    val mime = MusicMetadataWriter.sniffMimeType(cover).toByteArray()
    val out = ByteArrayOutputStream()
    out.write(intBytes(3))
    out.write(intBytes(mime.size))
    out.write(mime)
    out.write(intBytes(0))
    repeat(4) { out.write(intBytes(0)) }
    out.write(intBytes(cover.size))
    out.write(cover)
    return out.toByteArray()
}
