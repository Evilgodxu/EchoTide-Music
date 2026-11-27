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

/** 补全结果：写入字段数、无可写或失败 */
internal sealed interface MetadataFillOutcome {
    data class Filled(val fieldCount: Int) : MetadataFillOutcome

    /** 候选字段本地均已有值 */
    data object NothingToFill : MetadataFillOutcome

    data object Failed : MetadataFillOutcome
}

/** 应用一次补全；字段口径见 fillPlan，封面不变 */
internal suspend fun applyMetadataFill(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    currentFields: Map<TagField, String>,
    candidate: NeteaseSongSearchResult,
    overwrite: Boolean,
): MetadataFillOutcome {
    if (!track.isLocalAudioSource) return MetadataFillOutcome.Failed
    // 音轨/碟号/版权仅在详情接口，补齐失败用原值
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
