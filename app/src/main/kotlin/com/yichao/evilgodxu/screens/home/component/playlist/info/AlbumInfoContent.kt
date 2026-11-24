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
 * 专辑页「信息」栏：陈列一首曲目的详细信息。
 *
 * 展示哪一首由调用方定（见 [AlbumPlaylistPage]：本专辑正在播放时取正在播放那首，否则取首曲），
 * 与头图封面同源，一处口径同时决定封面与信息。
 *
 * 全部内容由曲目实时读取，不另存一份副本：标签来自音频文件内嵌标签（缺失项回落到曲目内存态），
 * 音频规格来自文件本身的容器头与平台元数据。
 *
 * 补全沿用元数据编辑页的同一套交互与落地路径：先在同一组候选对话框里挑条目、选补全方式，
 * 再由 [applyMetadataFill] 写回文件，写成功后重读这一份信息。
 */
@Composable
internal fun AlbumInfoContent(
    track: MusicTrack?,
    playbackState: MusicPlaybackState,
    // 标签与音频规格都要读文件，标签栏未切到本页时不读：两页常驻合成树，读不读由是否看得到决定
    visible: Boolean,
) {
    if (track == null) {
        EmptyHint(text = stringResource(R.string.playlist_empty))
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestAllFilesAccess = rememberAllFilesAccessRequest()
    // 补全写回文件后标签与规格都变了，重读一遍才与文件一致
    var reloadVersion by remember(track.id) { mutableIntStateOf(0) }
    val info by produceState<AlbumTrackInfo?>(initialValue = null, track.id, reloadVersion, visible) {
        if (!visible) return@produceState
        value = readAlbumTrackInfo(context, track)
    }
    // 补全流程的界面状态：候选对话框 → 补全方式对话框 → 写入中。
    // 候选与来源本身由播放状态持有（与编辑页共用同一份），这里只管这两级对话框的开关与选中项
    var fillVisible by remember(track.id) { mutableStateOf(false) }
    var fillSelectedId by remember(track.id) { mutableStateOf<Long?>(null) }
    var fillModeVisible by remember(track.id) { mutableStateOf(false) }
    var fillApplying by remember(track.id) { mutableStateOf(false) }
    var fillOverwrite by remember(track.id) { mutableStateOf(false) }

    fun openFill() {
        // 信息未就绪时补全计划没有比较基准，宁可等这一瞬读完也不按空字段猜
        if (info == null || fillApplying) return
        // 补全要重写音频文件：无权限时就地申请，本次不进入候选
        if (!ensureTrackFileWritable(requestAllFilesAccess)) return
        fillSelectedId = null
        fillModeVisible = false
        // 每次重新进入候选都回到「仅补全」：全覆盖是破坏性的一档，须由用户当次显式切换
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
        // 写入含联网详情请求与整段音频重写，交给常驻作用域：中途离开本页不该把已开始的写入取消
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
        AlbumInfoRow(stringResource(R.string.metadata_field_artist), loaded.fields[TagField.ARTIST])
        AlbumInfoRow(stringResource(R.string.metadata_field_album), loaded.fields[TagField.ALBUM])
        AlbumInfoRow(stringResource(R.string.metadata_field_track_number), loaded.fields[TagField.TRACK_NUMBER])
        AlbumInfoRow(stringResource(R.string.metadata_field_genre), loaded.fields[TagField.GENRE])
        AlbumInfoRow(stringResource(R.string.metadata_field_date), loaded.fields[TagField.DATE])
        format?.bitrate?.let {
            AlbumInfoRow(stringResource(R.string.audio_info_bitrate), stringResource(R.string.audio_info_value_kbps, it))
        }
        format?.sampleRate?.let {
            AlbumInfoRow(
                stringResource(R.string.playlist_album_info_sample_rate),
                stringResource(R.string.audio_info_value_khz_list, formatSampleRateKHz(it)),
            )
        }
        format?.format?.takeIf { it.isNotBlank() }?.let {
            AlbumInfoRow(stringResource(R.string.playlist_album_info_codec), it)
        }
        AlbumInfoRow(stringResource(R.string.playlist_album_info_track_duration), formatTime(loaded.durationMs))
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
        // 补全方式对话框的取消回到候选项，而不是退出整个补全流程
        onDismiss = {
            fillModeVisible = false
            fillVisible = true
        },
        onConfirm = ::applyFill,
    )
}

// 信息栏的展示内容：标签按字段陈列，音频规格只在文件里读得到时才给出
private class AlbumTrackInfo(
    /** 内嵌标签；标题/艺术家/专辑在文件未写标签时回落到曲目内存态 */
    val fields: Map<TagField, String>,
    val durationMs: Long,
    val path: String,
    /** 文件自身的格式、采样率、比特率等；读不到时为 null，对应行不展示 */
    val format: AudioSignalPathFormat?,
)

// 读取一份展示内容：标签读内嵌值（比曲目内存态更接近文件实况），规格读文件容器头与平台元数据
private suspend fun readAlbumTrackInfo(context: Context, track: MusicTrack): AlbumTrackInfo {
    val tags = TrackMetadataEditor.read(context, track)?.fields
    val format = withContext(Dispatchers.IO) { TrackAudioInfoReader.readIdleFormat(context, track) }
    val fields = TagField.entries.mapNotNull { field ->
        // 标题/艺术家/专辑的回落口径与元数据编辑页一致：文件里没写标签时，扫描出的名字比空行更有用
        val value = when (field) {
            TagField.TITLE -> tags?.get(field)?.takeIf { it.isNotBlank() } ?: track.title.takeIf { it.isNotBlank() }
            TagField.ARTIST -> tags?.get(field)?.takeIf { it.isNotBlank() } ?: track.artist.takeIf { it.isNotBlank() }
            TagField.ALBUM -> tags?.get(field)?.takeIf { it.isNotBlank() } ?: track.albumName.takeIf { it.isNotBlank() }
            else -> tags?.get(field)
        }
        value?.takeIf { it.isNotBlank() }?.let { field to it }
    }.toMap()
    return AlbumTrackInfo(
        fields = fields,
        durationMs = track.duration,
        path = track.path.ifBlank { track.audioUri },
        format = format,
    )
}

// 补全结果提示：写入成功报写入项数，无字段可写与写入失败分别给出各自的交代
private fun fillResultMessage(context: Context, outcome: MetadataFillOutcome): String = when (outcome) {
    is MetadataFillOutcome.Filled -> context.resources.getQuantityString(
        R.plurals.metadata_fill_done,
        outcome.fieldCount,
        outcome.fieldCount,
    )

    MetadataFillOutcome.NothingToFill -> context.getString(R.string.metadata_fill_nothing)
    MetadataFillOutcome.Failed -> context.getString(R.string.playlist_album_info_fill_failed)
}

// 补全入口：与页面其余图标动作同为图标按钮；联网与写盘期间换成进度圈并停止响应
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

// 信息行：标签居左、取值居右并单行截断——取值过长时不该把行高撑成两行
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

// 文件路径独占一段两行排布：路径是这一栏里唯一会被截断就失去意义的取值，
// 尾部的文件名与目录名都要看得到，故不塞进单行右对齐的信息行
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
