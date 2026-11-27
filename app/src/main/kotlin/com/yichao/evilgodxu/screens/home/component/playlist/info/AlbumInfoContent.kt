package com.yichao.evilgodxu.screens.home.component.playlist.info

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.metadata.TrackMetadataEditor
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.panel.MetadataFillOutcome
import com.yichao.evilgodxu.data.music.panel.applyMetadataFill
import com.yichao.evilgodxu.data.music.panel.searchMetadataCandidates
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.permission.ensureTrackFileWritable
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest
import com.yichao.evilgodxu.screens.home.component.playlist.EmptyHint
import com.yichao.evilgodxu.ui.component.FillModeDialog
import com.yichao.evilgodxu.ui.component.MetadataFillDialog
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.utils.formatSampleRateKHz
import com.yichao.evilgodxu.utils.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 专辑页「信息」栏：展示调用方指定的那首曲目，与头图同源。
 * 标签与规格实时读文件，补全写回后重读
 */
@Composable
internal fun AlbumInfoContent(
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    // 不可见时不读文件；两页常驻合成树
    visible: Boolean,
) {
    if (track == null) {
        EmptyHint(text = stringResource(R.string.playlist_empty))
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestAllFilesAccess = rememberAllFilesAccessRequest()
    // 写回后重读，才与文件一致
    var reloadVersion by remember(track.id) { mutableIntStateOf(0) }
    val info by produceState<AlbumTrackInfo?>(initialValue = null, track.id, reloadVersion, visible) {
        if (!visible) return@produceState
        value = readAlbumTrackInfo(context, track)
    }
    // 补全流程：候选 → 方式 → 写入
    var fillVisible by remember(track.id) { mutableStateOf(false) }
    var fillSelectedId by remember(track.id) { mutableStateOf<Long?>(null) }
    var fillModeVisible by remember(track.id) { mutableStateOf(false) }
    var fillApplying by remember(track.id) { mutableStateOf(false) }
    var fillOverwrite by remember(track.id) { mutableStateOf(false) }

    fun openFill() {
        // 信息未就绪不补全，避免按空字段猜
        if (info == null || fillApplying) return
        // 写文件需权限，无权限先申请
        if (!ensureTrackFileWritable(requestAllFilesAccess)) return
        fillSelectedId = null
        fillModeVisible = false
        // 每次进入都回到「仅补全」，全覆盖须显式选
        fillOverwrite = false
        fillVisible = true
        scope.launch { searchMetadataCandidates(playbackState, track, playbackState.metadataFillSource) }
    }

    fun selectFillSource(source: MusicSearchSource) {
        if (source == playbackState.metadataFillSource) return
        playbackState.metadataFillSource = source
        fillSelectedId = null
        scope.launch { searchMetadataCandidates(playbackState, track, source) }
    }

    fun reloadFill() {
        fillSelectedId = null
        scope.launch { searchMetadataCandidates(playbackState, track, playbackState.metadataFillSource) }
    }

    fun cancelFill() {
        fillVisible = false
        fillModeVisible = false
        fillSelectedId = null
        playbackState.metadataFillCandidates = emptyList()
    }

    fun applyFill() {
        val loaded = info ?: return
        if (fillApplying) return
        val candidate = playbackState.metadataFillCandidates.firstOrNull { it.id == fillSelectedId } ?: return
        fillApplying = true
        // 交给常驻作用域，离开本页不取消写入
        playbackState.playbackScope.launch {
            val outcome = runCatching {
                applyMetadataFill(context, playbackState, track, loaded.fields, candidate, fillOverwrite)
            }.getOrElse { MetadataFillOutcome.Failed }
            fillApplying = false
            fillModeVisible = false
            fillSelectedId = null
            playbackState.metadataFillCandidates = emptyList()
            Toast.makeText(context, fillResultMessage(context, outcome), Toast.LENGTH_SHORT).show()
            if (outcome is MetadataFillOutcome.Filled) reloadVersion++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = info?.fields?.get(TagField.TITLE) ?: track.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FillActionButton(
                loading = fillApplying,
                enabled = info != null && !fillApplying,
                onClick = ::openFill,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        val loaded = info ?: return@Column
        val format = loaded.format
        // 先标签字段再文件规格，路径独占末段；曲目号由「歌曲」列表承担
        AlbumInfoRow(stringResource(R.string.metadata_field_artist), loaded.fields[TagField.ARTIST])
        AlbumInfoRow(stringResource(R.string.metadata_field_album), loaded.fields[TagField.ALBUM])
        AlbumInfoRow(stringResource(R.string.metadata_field_language), loaded.fields[TagField.LANGUAGE])
        AlbumInfoRow(stringResource(R.string.metadata_field_genre), loaded.fields[TagField.GENRE])
        AlbumInfoRow(stringResource(R.string.metadata_field_date), loaded.fields[TagField.DATE])
        AlbumInfoRow(stringResource(R.string.playlist_album_info_track_duration), formatTime(loaded.durationMs))
        // 位深仅无损可读，不推算
        format?.bitDepth?.let {
            AlbumInfoRow(stringResource(R.string.playlist_album_info_bit_depth), stringResource(R.string.audio_info_value_bit, it))
        }
        format?.sampleRate?.let {
            AlbumInfoRow(
                stringResource(R.string.playlist_album_info_sample_rate),
                stringResource(R.string.audio_info_value_khz_list, formatSampleRateKHz(it)),
            )
        }
        format?.bitrate?.let {
            AlbumInfoRow(stringResource(R.string.audio_info_bitrate), stringResource(R.string.audio_info_value_kbps, it))
        }
        loaded.codec?.let {
            AlbumInfoRow(stringResource(R.string.playlist_album_info_codec), it)
        }
        AlbumInfoPathRow(loaded.path)
    }
    MetadataFillDialog(
        visible = fillVisible,
        playbackState = playbackState,
        selectedId = fillSelectedId,
        onSourceSelected = ::selectFillSource,
        onReload = ::reloadFill,
        onCandidateSelected = { candidate: NeteaseSongSearchResult -> fillSelectedId = candidate.id },
        onConfirm = {
            if (fillSelectedId != null) {
                fillVisible = false
                fillModeVisible = true
            }
        },
        onCancel = ::cancelFill,
    )
    FillModeDialog(
        visible = fillModeVisible,
        overwrite = fillOverwrite,
        applying = fillApplying,
        onOverwriteChange = { fillOverwrite = it },
        // 取消回到候选项，不退出整个流程
        onDismiss = {
            fillModeVisible = false
            fillVisible = true
        },
        onConfirm = ::applyFill,
    )
}

// 展示内容：标签按字段，规格读得到才给
private class AlbumTrackInfo(
    // 内嵌标签；标题/艺术家/专辑可回落内存态
    val fields: Map<TagField, String>,
    val durationMs: Long,
    val path: String,
    // 码流名，读不到按扩展名兜底
    val codec: String?,
    // 采样率/位深/比特率；null 时不展示
    val format: AudioSignalPathFormat?,
)

// 标签读内嵌值，规格读容器头与平台元数据
private suspend fun readAlbumTrackInfo(context: Context, track: MusicTrack): AlbumTrackInfo {
    val tags = TrackMetadataEditor.read(context, track)?.fields
    val format = withContext(Dispatchers.IO) { TrackAudioInfoReader.readIdleFormat(context, track) }
    val fields = TagField.entries.mapNotNull { field ->
        // 标题/艺术家/专辑回落口径与编辑页一致
        val value = when (field) {
            TagField.TITLE -> tags?.get(field)?.takeIf { it.isNotBlank() } ?: track.title.takeIf { it.isNotBlank() }
            TagField.ARTIST -> tags?.get(field)?.takeIf { it.isNotBlank() } ?: track.artist.takeIf { it.isNotBlank() }
            TagField.ALBUM -> tags?.get(field)?.takeIf { it.isNotBlank() } ?: track.albumName.takeIf { it.isNotBlank() }
            else -> tags?.get(field)
        }
        value?.takeIf { it.isNotBlank() }?.let { field to it }
    }.toMap()
    // 容器头与 mime 都读不出时用扩展名兜底
    val codec = format?.format?.takeIf { it.isNotBlank() }
        ?: track.path.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.uppercase()
    return AlbumTrackInfo(
        fields = fields,
        durationMs = track.duration,
        path = track.path.ifBlank { track.audioUri },
        codec = codec,
        format = format,
    )
}

// 结果提示：成功报项数，无可写与失败分别提示
private fun fillResultMessage(context: Context, outcome: MetadataFillOutcome): String = when (outcome) {
    is MetadataFillOutcome.Filled -> context.resources.getQuantityString(
        R.plurals.metadata_fill_done,
        outcome.fieldCount,
        outcome.fieldCount,
    )

    MetadataFillOutcome.NothingToFill -> context.getString(R.string.metadata_fill_nothing)
    MetadataFillOutcome.Failed -> context.getString(R.string.playlist_album_info_fill_failed)
}

// 补全按钮；联网与写盘时换进度圈并禁用
@Composable
private fun FillActionButton(
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Icon(
                imageVector = AppIcons.CloudSync,
                contentDescription = stringResource(R.string.metadata_fill_action),
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// 信息行：标签左、取值右，单行截断
@Composable
private fun AlbumInfoRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: stringResource(R.string.metadata_field_empty),
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// 路径独占两行，尾部文件名与目录都要可见
@Composable
private fun AlbumInfoPathRow(path: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.audio_info_file_path),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
        Text(
            text = path,
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}
