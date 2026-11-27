package com.yichao.evilgodxu.data.music.metadata

/** 封面三态：Keep 保留，Set 写入，Remove 删除 */
sealed interface CoverUpdate {
    /** 本次不改封面，保留文件里的既有值 */
    data object Keep : CoverUpdate

    /** 写入封面；非数据类，避免逐字节比较 */
    class Set(val bytes: ByteArray) : CoverUpdate

    /** 删除内嵌封面 */
    data object Remove : CoverUpdate
}

/** 标签字段标识，读写与界面共用；顺序同表单 */
enum class TagField {
    TITLE,
    ARTIST,
    ALBUM_ARTIST,
    ALBUM,
    DATE,
    LANGUAGE,
    GENRE,
    TRACK_NUMBER,
    DISC_NUMBER,
    LYRICIST,
    COMPOSER,
    COPYRIGHT,
    COMMENT,
    RATING,
    REPLAY_GAIN_TRACK_GAIN,
    REPLAY_GAIN_TRACK_PEAK,
    REPLAY_GAIN_ALBUM_GAIN,
    REPLAY_GAIN_ALBUM_PEAK,
    REPLAY_GAIN_REFERENCE_LOUDNESS,
    LYRICS,
    ;

    companion object {
        /** 基本信息分组的字段，按表单展示顺序 */
        val BASIC: List<TagField> = listOf(TITLE, ARTIST, ALBUM_ARTIST, ALBUM, DATE, LANGUAGE, GENRE)

        /** 曲目信息分组的字段 */
        val TRACK: List<TagField> = listOf(TRACK_NUMBER, DISC_NUMBER)

        /** 参与人员与其它信息分组的字段 */
        val CREDITS: List<TagField> = listOf(LYRICIST, COMPOSER, COPYRIGHT, COMMENT, RATING)

        /** ReplayGain 分组的字段 */
        val REPLAY_GAIN: List<TagField> =
            listOf(REPLAY_GAIN_TRACK_GAIN, REPLAY_GAIN_TRACK_PEAK, REPLAY_GAIN_ALBUM_GAIN, REPLAY_GAIN_ALBUM_PEAK, REPLAY_GAIN_REFERENCE_LOUDNESS)
    }
}

/** 字段集合：缺项保留原值，空串表示清除 */
class TrackTagFields private constructor(private val values: Map<TagField, String>) {

    /** 该字段的取值；未参与本次操作时为 null */
    operator fun get(field: TagField): String? = values[field]

    /** 参与本次操作的字段，按声明顺序 */
    fun entries(): List<Pair<TagField, String>> = TagField.entries.mapNotNull { field ->
        values[field]?.let { field to it }
    }

    companion object {
        val EMPTY: TrackTagFields = TrackTagFields(emptyMap())

        /** 由非空字段构造 */
        fun build(block: Builder.() -> Unit): TrackTagFields = Builder().apply(block).build()

        /** 以既有集合为底覆盖部分字段 */
        fun overlay(base: TrackTagFields, block: Builder.() -> Unit): TrackTagFields =
            Builder(base.values.toMutableMap()).apply(block).build()
    }

    class Builder internal constructor(private val map: MutableMap<TagField, String> = LinkedHashMap()) {
        /** 值为 null 不参与本次操作，与空串区分 */
        fun put(field: TagField, value: String?) {
            if (value != null) map[field] = value
        }

        internal fun build(): TrackTagFields = TrackTagFields(map.toMap())
    }
}
