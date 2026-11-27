package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
import com.yichao.evilgodxu.screens.metadata.MetadataUiState
import com.yichao.evilgodxu.ui.component.FillModeDialog
import com.yichao.evilgodxu.ui.component.MetadataFillDialog
import com.yichao.evilgodxu.ui.component.player.LocalCoverDialog
import com.yichao.evilgodxu.ui.component.player.loadRecentCovers
import com.yichao.evilgodxu.ui.copyToClipboard
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑表单：封面 + 字段 + 歌词，自动保存 */
@Composable
internal fun MetadataForm(
    uiState: MetadataUiState,
    playbackState: MusicPlaybackState,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
    onFieldChange: (TagField, String) -> Unit,
    onLyricRawChange: (String) -> Unit,
    onLyricTranslationChange: (Int, String) -> Unit,
    onLyricLineTranslate: (Int) -> Unit,
    onLyricsWholeModeToggle: () -> Unit,
    onCoverSelected: (ByteArray) -> Unit,
    onCoverRemoved: () -> Unit,
    onCoverExport: () -> Unit,
    onSameAlbumRequest: () -> Unit,
    onSameAlbumDismiss: () -> Unit,
    onCoverRefreshOpen: () -> Unit,
    onCoverRefreshSourceSelected: (MusicSearchSource) -> Unit,
    onCoverRefreshReload: () -> Unit,
    onCoverRefreshCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onCoverRefreshConfirm: () -> Unit,
    onCoverRefreshCancel: () -> Unit,
    onFillOpen: () -> Unit,
    onFillSourceSelected: (MusicSearchSource) -> Unit,
    onFillReload: () -> Unit,
    onFillCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onFillOverwriteChange: (Boolean) -> Unit,
    onFillConfirm: () -> Unit,
    onFillApply: () -> Unit,
    onFillModeDismiss: () -> Unit,
    onFillCancel: () -> Unit,
    onReplayGainCompute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 全文复制提示不带内容
    val wholeCopiedHint = stringResource(R.string.metadata_lyrics_whole_copied)
    // 本地封面候选：与播放面板同源，选中后读字节落盘
    var localCovers by remember { mutableStateOf<List<RecentCover>>(emptyList()) }
    var showLocalCover by remember { mutableStateOf(false) }
    var selectedLocalCover by remember { mutableStateOf<RecentCover?>(null) }
    var localCoverSaving by remember { mutableStateOf(false) }
    // 在线流不展示表单
    if (!uiState.editable) {
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.metadata_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // 封面浮层：同专辑与在线刷新
    MetadataCoverOverlays(
        uiState = uiState,
        playbackState = playbackState,
        onSameAlbumDismiss = onSameAlbumDismiss,
        onSameAlbumSelected = onCoverSelected,
        onCoverRefreshSourceSelected = onCoverRefreshSourceSelected,
        onCoverRefreshReload = onCoverRefreshReload,
        onCoverRefreshCandidateSelected = onCoverRefreshCandidateSelected,
        onCoverRefreshConfirm = onCoverRefreshConfirm,
        onCoverRefreshCancel = onCoverRefreshCancel,
    )
    // 在线补全：候选后二级选补全方式
    MetadataFillDialog(
        visible = uiState.fillVisible,
        playbackState = playbackState,
        selectedId = uiState.fillSelectedId,
        onSourceSelected = onFillSourceSelected,
        onReload = onFillReload,
        onCandidateSelected = onFillCandidateSelected,
        onConfirm = onFillConfirm,
        onCancel = onFillCancel,
    )
    FillModeDialog(
        visible = uiState.fillModeVisible,
        overwrite = uiState.fillOverwrite,
        applying = uiState.fillApplying,
        onOverwriteChange = onFillOverwriteChange,
        onConfirm = onFillApply,
        onDismiss = onFillModeDismiss,
    )
    // 复用播放面板的候选选择
    LocalCoverDialog(
        visible = showLocalCover,
        candidates = localCovers,
        selected = selectedLocalCover,
        saving = localCoverSaving,
        onSelected = { selectedLocalCover = it },
        onConfirm = {
            val cover = selectedLocalCover
            if (cover != null && !localCoverSaving) {
                localCoverSaving = true
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(cover.uri)?.use { it.readBytes() } }.getOrNull()
                    }
                    localCoverSaving = false
                    if (bytes != null && bytes.isNotEmpty()) {
                        onCoverSelected(bytes)
                        showLocalCover = false
                        selectedLocalCover = null
                        localCovers = emptyList()
                    }
                }
            }
        },
        onCancel = {
            showLocalCover = false
            selectedLocalCover = null
            localCovers = emptyList()
        },
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            // 点击空白即结束编辑并收起键盘
            .pointerInput(Unit) { detectTapGestures(onTap = { onEditEnd() }) }
            // 底部让出键盘/导航栏，取并集不叠加
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .verticalScroll(rememberScrollState()),
    ) {
        // 读取期间不展示表单
        if (uiState.loading) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).size(28.dp),
                strokeWidth = 2.dp,
            )
            return@Column
        }
        val labels = tagFieldLabels()
        // 封面不套卡片，保留可视面积
        MetadataCoverEditor(
            title = uiState.value(TagField.TITLE),
            artist = uiState.value(TagField.ARTIST),
            coverBytes = uiState.coverBytes,
            enabled = !uiState.saving,
            exporting = uiState.exportingCover,
            // 打开本地封面候选
            onPickCover = {
                selectedLocalCover = null
                showLocalCover = true
                scope.launch { localCovers = loadRecentCovers(context) }
            },
            onSaveCover = onCoverExport,
            onPickSameAlbum = onSameAlbumRequest,
            onOnlineRefresh = onCoverRefreshOpen,
            onRemoveCover = onCoverRemoved,
        )
        MetadataFormHint(stringResource(R.string.metadata_edit_hint))
        MetadataStatusText(
            message = uiState.message,
            messageIsError = uiState.messageIsError,
            modifier = Modifier.fillMaxWidth(),
        )
        // 补全入口放在基本信息标题右侧
        MetadataSection(
            title = stringResource(R.string.metadata_section_basic),
            action = {
                MetadataSectionAction(
                    icon = AppIcons.CloudSync,
                    contentDescription = stringResource(R.string.metadata_fill_action),
                    busy = uiState.fillApplying,
                    enabled = !uiState.saving && !uiState.loading,
                    onClick = onFillOpen,
                )
            },
        ) {
            TagField.BASIC.forEach { FieldEntry(it, labels, uiState, onFieldChange, onEditStart, onEditEnd) }
        }
        MetadataSection(title = stringResource(R.string.metadata_section_track)) {
            TagField.TRACK.forEach { FieldEntry(it, labels, uiState, onFieldChange, onEditStart, onEditEnd) }
        }
        MetadataSection(title = stringResource(R.string.metadata_section_credits)) {
            TagField.CREDITS.forEach { field ->
                if (field == TagField.RATING) {
                    RatingEntry(
                        label = labels.getValue(field),
                        value = uiState.value(field),
                        enabled = !uiState.saving,
                        onValueChange = { onFieldChange(field, it) },
                    )
                } else {
                    FieldEntry(field, labels, uiState, onFieldChange, onEditStart, onEditEnd)
                }
            }
        }
        MetadataSection(
            title = stringResource(R.string.metadata_section_replay_gain),
            action = {
                MetadataSectionAction(
                    icon = AppIcons.QueryStats,
                    contentDescription = stringResource(R.string.metadata_replay_gain_compute),
                    busy = uiState.replayGainComputing,
                    enabled = !uiState.saving && !uiState.loading,
                    onClick = onReplayGainCompute,
                )
            },
        ) {
            TagField.REPLAY_GAIN.forEach { FieldEntry(it, labels, uiState, onFieldChange, onEditStart, onEditEnd) }
        }
        LyricsSection(
            lines = uiState.lyricLines,
            unparsable = uiState.lyricsUnparsable,
            editing = uiState.editing,
            lyricLineDraft = uiState.lyricLineDraft,
            wholeMode = uiState.lyricsWholeMode,
            enabled = !uiState.saving,
            translatingLine = uiState.translatingLine,
            onRawChange = onLyricRawChange,
            onTranslationChange = onLyricTranslationChange,
            onTranslateLine = onLyricLineTranslate,
            // 点击时才编码整篇歌词
            onCopyWhole = {
                copyToClipboard(
                    context = context,
                    text = MusicMetadataCache.encodeLyrics(uiState.lyricLines),
                    toastText = wholeCopiedHint,
                )
            },
            onWholeModeToggle = onLyricsWholeModeToggle,
            onStartEdit = onEditStart,
            onEditDone = onEditEnd,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// 单字段条目接线
@Composable
private fun FieldEntry(
    field: TagField,
    labels: Map<TagField, String>,
    uiState: MetadataUiState,
    onFieldChange: (TagField, String) -> Unit,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
) {
    // 星标与数值也按文本编辑
    MetadataEntry(
        label = labels.getValue(field),
        value = uiState.value(field),
        editing = uiState.editing == MetadataEditTarget.Field(field),
        enabled = !uiState.saving,
        onValueChange = { onFieldChange(field, it) },
        onStartEdit = { onEditStart(MetadataEditTarget.Field(field)) },
        onEditDone = onEditEnd,
    )
}

// 评分：点同颗星清除
@Composable
private fun RatingEntry(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val rating = value.trim().toIntOrNull() ?: 0
    MetadataRowContainer(enabled = enabled) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.9f),
        )
        Row(
            modifier = Modifier.weight(1.3f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rating <= 0) {
                Text(
                    text = stringResource(R.string.metadata_field_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                )
            }
            for (star in 1..RATING_MAX_STARS) {
                Icon(
                    imageVector = if (star <= rating) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    contentDescription = null,
                    tint = if (star <= rating) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(20.dp)
                        .clickable(enabled = enabled) {
                            onValueChange(if (rating == star) "" else star.toString())
                        },
                )
            }
        }
    }
}

// 五星，与 POPM 取值域一致
private const val RATING_MAX_STARS = 5

// 字段标识到文案的映射
@Composable
private fun tagFieldLabels(): Map<TagField, String> = mapOf(
    TagField.TITLE to stringResource(R.string.metadata_field_title),
    TagField.ARTIST to stringResource(R.string.metadata_field_artist),
    TagField.ALBUM_ARTIST to stringResource(R.string.metadata_field_album_artist),
    TagField.ALBUM to stringResource(R.string.metadata_field_album),
    TagField.DATE to stringResource(R.string.metadata_field_date),
    TagField.LANGUAGE to stringResource(R.string.metadata_field_language),
    TagField.GENRE to stringResource(R.string.metadata_field_genre),
    TagField.TRACK_NUMBER to stringResource(R.string.metadata_field_track_number),
    TagField.DISC_NUMBER to stringResource(R.string.metadata_field_disc_number),
    TagField.LYRICIST to stringResource(R.string.metadata_field_lyricist),
    TagField.COMPOSER to stringResource(R.string.metadata_field_composer),
    TagField.COPYRIGHT to stringResource(R.string.metadata_field_copyright),
    TagField.COMMENT to stringResource(R.string.metadata_field_comment),
    TagField.RATING to stringResource(R.string.metadata_field_rating),
    TagField.REPLAY_GAIN_TRACK_GAIN to stringResource(R.string.metadata_field_replay_gain_track_gain),
    TagField.REPLAY_GAIN_TRACK_PEAK to stringResource(R.string.metadata_field_replay_gain_track_peak),
    TagField.REPLAY_GAIN_ALBUM_GAIN to stringResource(R.string.metadata_field_replay_gain_album_gain),
    TagField.REPLAY_GAIN_ALBUM_PEAK to stringResource(R.string.metadata_field_replay_gain_album_peak),
    TagField.REPLAY_GAIN_REFERENCE_LOUDNESS to stringResource(R.string.metadata_field_replay_gain_reference_loudness),
    TagField.LYRICS to stringResource(R.string.metadata_section_lyrics),
)
