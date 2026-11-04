package com.yichao.evilgodxu.data.music.metadata

// MP4 的普通文本原子：字段到 ilst 原子名的映射（© 为 0xA9）。
// 读取端复用同一张表，避免写出的原子名与读取端识别的名字各写一份
internal object Mp4TagSchema {
    val ATOMS: Map<TagField, String> = mapOf(
        TagField.TITLE to "©nam",
        TagField.ARTIST to "©ART",
        TagField.ALBUM_ARTIST to "aART",
        TagField.ALBUM to "©alb",
        TagField.GENRE to "©gen",
        TagField.DATE to "©day",
        TagField.COMPOSER to "©wrt",
        TagField.COPYRIGHT to "cprt",
        TagField.COMMENT to "©cmt",
        TagField.TRACK_NUMBER to "trkn",
        TagField.DISC_NUMBER to "disk",
        TagField.LYRICS to "©lyr",
    )

    // MP4 的自由格式原子（----:com.apple.iTunes:键）：无专用原子名的字段按此承载。读取端复用同一张表
    val FREEFORM_KEYS: Map<TagField, String> = mapOf(
        TagField.LANGUAGE to "LANGUAGE",
        TagField.LYRICIST to "LYRICIST",
        TagField.RATING to "RATING",
        TagField.REPLAY_GAIN_TRACK_GAIN to "REPLAYGAIN_TRACK_GAIN",
        TagField.REPLAY_GAIN_TRACK_PEAK to "REPLAYGAIN_TRACK_PEAK",
        TagField.REPLAY_GAIN_ALBUM_GAIN to "REPLAYGAIN_ALBUM_GAIN",
        TagField.REPLAY_GAIN_ALBUM_PEAK to "REPLAYGAIN_ALBUM_PEAK",
        TagField.REPLAY_GAIN_REFERENCE_LOUDNESS to "REPLAYGAIN_REFERENCE_LOUDNESS",
    )

    const val FREEFORM_MEAN = "com.apple.iTunes"

    // MP4 的自由格式原子标识：键名与值分别在 name/data 子原子中，故多个自由格式原子共用同一标识
    const val FREE_FORM_ATOM = "----"
}
