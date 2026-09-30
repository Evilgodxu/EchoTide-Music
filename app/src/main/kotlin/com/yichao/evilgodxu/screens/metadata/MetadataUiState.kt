package com.yichao.evilgodxu.screens.metadata

import com.yichao.evilgodxu.data.music.model.LyricLine

// 可编辑的基本信息字段：点击条目进入编辑态时用它标识正在改的是哪一行
enum class MetadataField {
    TITLE,
    ARTIST,
    ALBUM,
}

// 正在编辑的条目：基本信息字段与歌词行共用一个编辑位，同时只允许一行处于编辑态
sealed interface MetadataEditTarget {
    data class Field(val field: MetadataField) : MetadataEditTarget

    // 歌词行按解析结果的下标标识：同一行的时间戳可能重复，文本也可重名，只有位置能唯一确定一行
    data class LyricLineAt(val index: Int) : MetadataEditTarget

    // 歌词翻译行：与原文行分属不同编辑位，互不干扰，可独立进入与退出编辑
    data class LyricTranslationAt(val index: Int) : MetadataEditTarget

    // 歌词全文编辑：整篇以增强 LRC 文本一次性编辑，与逐行编辑共用同一编辑位，二者互斥
    data object LyricsWhole : MetadataEditTarget
}

// 元数据编辑页状态：每次进入页面重新读取音频文件的内嵌标签回填表单，
// 编辑中的字段与文件原值分列，使「未改动」与「改为空」可区分
data class MetadataUiState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    // 歌词按解析出的行分列展示：每行是独立条目，编辑也以行为单位进行
    val lyricLines: List<LyricLine> = emptyList(),
    // 文件里有歌词文本但解析不出行（时间戳格式不对）：与「本来就没有歌词」分开提示，
    // 否则用户会把格式问题当成歌词丢失
    val lyricsUnparsable: Boolean = false,
    // 封面预览：进入页面时从文件内嵌封面取回，用户选择新图或移除后替换
    val coverBytes: ByteArray? = null,
    // 文件内是否已有内嵌封面：决定「移除封面」是否可用
    val coverPresent: Boolean = false,
    // 载入中：读取标签期间不展示表单，避免旧内容与新内容在界面上交叠
    val loading: Boolean = true,
    // 正在写回音频文件：期间禁用编辑入口，避免写入过程中的改动与已有写入交错
    val saving: Boolean = false,
    // 目标曲目不可编辑（在线流无本地可写文件）
    val editable: Boolean = true,
    // 当前处于编辑态的条目；为 null 时全部条目为只读展示
    val editing: MetadataEditTarget? = null,
    // 歌词原文行的内联草稿：完整增强 LRC 文本，仅在原文行编辑态非空。
    // 文本需原样保留用户输入（含尚未成形的标签），不能由解析结果反推，故单独存放；
    // 放在状态里而非组件内，使「点击外部退出」也能提交草稿
    val lyricLineDraft: String? = null,
    // 自动保存结果提示：成功或失败原因，显示后可被下次保存覆盖
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    // 数据类含 ByteArray，默认的 equals/hashCode 按引用比较；
    // 该状态只用于重组驱动，不做相等性判定，故显式覆盖以消除编译期告警
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

// 表单初值快照：作为「哪些条目被改动过」的比较基准，写入成功后随之刷新。
// 歌词按解析后的行保存而非原始文本：界面以行为编辑单位，原文本的换行与空白差异不构成改动
data class MetadataFormSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val lyricLines: List<LyricLine>,
) {
    fun value(field: MetadataField): String = when (field) {
        MetadataField.TITLE -> title
        MetadataField.ARTIST -> artist
        MetadataField.ALBUM -> album
    }
}
