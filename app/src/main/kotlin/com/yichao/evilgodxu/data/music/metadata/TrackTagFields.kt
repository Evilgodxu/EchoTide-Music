package com.yichao.evilgodxu.data.music.metadata

/**
 * 内嵌标签字段的身份标识。
 *
 * 读写两侧共用同一组标识：读取端把各容器的异构键统一收敛到它，写入端再由它分派回各容器的键。
 * 没有这份共同标识，字段清单会在「读取」「写入」「界面」三处各存一份，增删一个字段要同步改三遍。
 *
 * 位置参数与界面表单的排列顺序一致，供界面按序生成条目。
 */
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

/**
 * 一次标签读写的字段集合。
 *
 * 以映射承载而非逐个具名字段：字段清单会随容器能力继续增长，映射让「增删一个字段」不必改动
 * 构造、拷贝与遍历三处；容器的键映射也天然按字段查找。
 *
 * 值缺失与空串语义不同且在两处一致：**缺失**表示该字段未参与本次操作 ——
 * 读取时是「文件里没有这个字段」，写入时是「保留文件原有值」；**空串**表示有值且值为空，
 * 写入即清除该字段。二者在读取侧的区分同样重要：未写入与写入空串是两回事。
 */
class TrackTagFields private constructor(private val values: Map<TagField, String>) {

    /** 该字段的取值；未参与本次操作时为 null */
    operator fun get(field: TagField): String? = values[field]

    /** 参与本次操作的字段：按声明顺序返回，写入端据此逐个落到容器的键上 */
    fun entries(): List<Pair<TagField, String>> = TagField.entries.mapNotNull { field ->
        values[field]?.let { field to it }
    }

    companion object {
        val EMPTY: TrackTagFields = TrackTagFields(emptyMap())

        /** 由非空字段构造：读取端组装结果与写入端按需覆盖都用它 */
        fun build(block: Builder.() -> Unit): TrackTagFields = Builder().apply(block).build()

        /** 以既有字段集合为底，覆盖其中的部分字段：界面的「只写改动过的项」由此表达 */
        fun overlay(base: TrackTagFields, block: Builder.() -> Unit): TrackTagFields =
            Builder(base.values.toMutableMap()).apply(block).build()
    }

    class Builder internal constructor(private val map: MutableMap<TagField, String> = LinkedHashMap()) {
        /** 值为 null 表示该字段不参与本次操作（保留原值），与空串区分 */
        fun put(field: TagField, value: String?) {
            if (value != null) map[field] = value
        }

        internal fun build(): TrackTagFields = TrackTagFields(map.toMap())
    }
}
