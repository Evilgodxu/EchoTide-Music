package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.api.enrichTrackDetail
import com.yichao.evilgodxu.data.music.metadata.CoverUpdate
import com.yichao.evilgodxu.data.music.metadata.METADATA_FILL_DETAIL_WAIT_MS
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.metadata.TrackMetadataEditor
import com.yichao.evilgodxu.data.music.metadata.TrackTagFields
import com.yichao.evilgodxu.data.music.metadata.fillPlan
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import kotlinx.coroutines.withTimeoutOrNull

/** 一次补全的落地结果：写入的字段数、无字段可写、写入失败 */
internal sealed interface MetadataFillOutcome {
    data class Filled(val fieldCount: Int) : MetadataFillOutcome

    /** 候选给出的字段本地都已有值：这次补全没有任何要写的内容 */
    data object NothingToFill : MetadataFillOutcome

    data object Failed : MetadataFillOutcome
}

/**
 * 应用一次补全：把用户在候选对话框里选中的条目写成该曲目的内嵌标签，并同步曲目内存态。
 *
 * 与元数据编辑页的补全共用同一条落地路径 —— 详情补全、补全计划与写回文件分别由
 * [enrichTrackDetail]、[fillPlan]、[TrackMetadataEditor.save] 承担，两处只有提交位置不同：
 * 编辑页把计划逐项交给表单的自动落盘（界面要回显），没有表单的调用方则在这里整批写回。
 * 「哪些字段该写」的口径因此仍只有 [fillPlan] 一处，不会两处各自演化。
 *
 * [currentFields] 是调用方读到的当前标签值，计划据它判断字段是否缺失或与平台值一致。
 * 封面不在补全范围内，[CoverUpdate.Keep] 保留文件里的既有封面。
 */
internal suspend fun applyMetadataFill(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    currentFields: Map<TagField, String>,
    candidate: NeteaseSongSearchResult,
    overwrite: Boolean,
): MetadataFillOutcome {
    if (!track.isLocalAudioSource) return MetadataFillOutcome.Failed
    // 音轨号、碟号、版权只在详情接口里，落地前补一次；失败就用手上这份
    val detailed = withTimeoutOrNull(METADATA_FILL_DETAIL_WAIT_MS) { enrichTrackDetail(candidate) } ?: candidate
    val plan = fillPlan(currentFields, detailed, overwrite)
    if (plan.isEmpty()) return MetadataFillOutcome.NothingToFill
    val fields = TrackTagFields.build { plan.forEach { (field, value) -> put(field, value) } }
    if (!TrackMetadataEditor.save(context, track, fields, CoverUpdate.Keep)) return MetadataFillOutcome.Failed
    playbackState.updateTrack(
        track.copy(
            title = fields[TagField.TITLE] ?: track.title,
            artist = fields[TagField.ARTIST] ?: track.artist,
            albumName = fields[TagField.ALBUM] ?: track.albumName,
        ),
    )
    return MetadataFillOutcome.Filled(plan.size)
}
