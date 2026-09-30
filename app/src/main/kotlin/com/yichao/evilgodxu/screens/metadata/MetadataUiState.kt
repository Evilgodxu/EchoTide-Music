package com.yichao.evilgodxu.screens.metadata

// 元数据编辑页状态：进入页面即读取音频文件的内嵌标签回填表单，
// 编辑中的字段与文件原值分列，使「未改动」与「改为空」可区分
data class MetadataUiState(
    // 编辑对象的文件名与时长：表单字段取自文件内嵌标签，文件名是「正在改哪个文件」的锚点
    val fileName: String = "",
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val lyrics: String = "",
    // 封面预览：进入页面时从文件内嵌封面取回一次，用户选择新图或移除后替换
    val coverBytes: ByteArray? = null,
    // 文件内是否已有内嵌封面：决定「移除封面」是否可点
    val coverPresent: Boolean = false,
    // 载入中：读取标签期间表单不可编辑，避免把空表单覆盖回文件
    val loading: Boolean = true,
    val saving: Boolean = false,
    // 目标曲目不可编辑（在线流无本地文件，或容器不支持写入）
    val editable: Boolean = true,
    // 保存结果提示：成功或失败原因，显示后可被下次保存覆盖
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    // 数据类含 ByteArray，默认的 equals/hashCode 按引用比较；
    // 该状态只用于重组驱动，不做相等性判定，故显式覆盖以消除编译期告警
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

// 表单初值快照：作为「哪些字段被改动过」的比较基准，保存成功后随之刷新
data class MetadataFormSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val lyrics: String,
)
