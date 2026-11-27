package com.yichao.evilgodxu.screens.metadata

import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.model.LyricLine

// 编辑位：字段与歌词行共用，同时只编辑一项
sealed interface MetadataEditTarget {
    data class Field(val field: TagField) : MetadataEditTarget

    // 按解析结果下标定位，行内容可能重复
    data class LyricLineAt(val index: Int) : MetadataEditTarget

    // 翻译行独立编辑，与原文互不干扰
    data class LyricTranslationAt(val index: Int) : MetadataEditTarget

    // 全文编辑：整篇增强 LRC，与逐行互斥
    data object LyricsWhole : MetadataEditTarget
}

/** 元数据页状态：字段用映射承载，未收录按空串 */
data class MetadataUiState(
    // 目标曲目 id，浮层据此取回曲目
    val trackId: Long = 0L,
    // 字段当前值，未收录按空串
    val fields: Map<TagField, String> = emptyMap(),
    // 歌词按行展示与编辑
    val lyricLines: List<LyricLine> = emptyList(),
    // 有文本却解析不出行，与无歌词分开提示
    val lyricsUnparsable: Boolean = false,
    // 封面预览，null 表示无封面
    val coverBytes: ByteArray? = null,
    // 载入中，读取期间不展示表单
    val loading: Boolean = true,
    // 写回中：不接受新编辑，已聚焦输入框仍可输入
    val saving: Boolean = false,
    // 在线流不可编辑
    val editable: Boolean = true,
    // 当前编辑项，null 时只读
    val editing: MetadataEditTarget? = null,
    // 原文行草稿：原样保留输入，放状态以便外部退出也提交
    val lyricLineDraft: String? = null,
    // 全文模式：决定展示形态，与编辑态分开
    val lyricsWholeMode: Boolean = false,
    // 补译中的行；接口限流，同时只跑一个
    val translatingLine: Int? = null,
    val sameAlbumVisible: Boolean = false,
    val sameAlbumLoading: Boolean = false,
    // 同专辑其它本地曲目封面
    val sameAlbumCovers: List<ByteArray> = emptyList(),
    // 复用首页封面搜索
    val coverRefreshVisible: Boolean = false,
    val coverRefreshSelectedId: Long? = null,
    val coverRefreshSaving: Boolean = false,
    val exportingCover: Boolean = false,
    // fillOverwrite 为 true 则覆盖
    val fillVisible: Boolean = false,
    val fillSelectedId: Long? = null,
    val fillModeVisible: Boolean = false,
    val fillApplying: Boolean = false,
    val fillOverwrite: Boolean = false,
    // 增益计算中，拒绝重复触发
    val replayGainComputing: Boolean = false,
    // 保存结果提示，可被下次覆盖
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    // 含 ByteArray，按引用比较以消除告警
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    /** 字段当前值，未收录即空串 */
    fun value(field: TagField): String = fields[field].orEmpty()
}
