package com.yichao.evilgodxu.data.music.metadata

// 普通文本原子名映射，读写共用
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

    // 自由格式原子键，读写共用
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

    // 自由格式原子标识，键与值在子原子中
    const val FREE_FORM_ATOM = "----"
}
