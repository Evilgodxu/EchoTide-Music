package com.yichao.evilgodxu.screens.metadata.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
import com.yichao.evilgodxu.screens.metadata.MetadataUiState
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 元数据编辑表单：内嵌封面 + 基本信息/曲目信息/参与人员/音量增益 + 歌词。
 *
 * 页面没有保存入口 —— 条目改动在输入停顿后自动写回音频文件，
 * 用户无需记住「改完要点保存」，也不会因切走页面而丢掉改动。
 */
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 图片选择统一按 image/* 过滤，选中后读回原始字节交给状态持有者
    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null && bytes.isNotEmpty()) onCoverSelected(bytes)
        }
    }
    // 无可写目标的曲目（纯在线流）不展示表单：编辑一份不会落盘的字段只会误导用户
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
    // 封面操作的浮层：同专辑选择与在线刷新，均以本页状态驱动
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
    Column(
        modifier = modifier
            .fillMaxSize()
            // 点击输入框以外的空白区域即结束当前编辑并收起键盘：编辑被点击的可交互控件消费，
            // 只有空白区命中根节点。编辑态收起由用户的明确意图驱动，不依赖异步焦点事件，
            // 也不跟随键盘显隐 —— 系统返回键收起键盘后输入框保持编辑态与焦点，
            // 用户得以继续查看刚输入的内容是否无误，确认无误再点空白处或按返回键收起
            .pointerInput(Unit) { detectTapGestures(onTap = { onEditEnd() }) }
            // 底部收紧：键盘展开时让出键盘，配合输入框自身的 bringIntoView 使输入框整体滚到键盘上方；
            // 键盘收起时让出系统导航栏高度，否则表单末项被三键导航的虚拟按键压住。
            // 两者取并集而非叠加：键盘高度已含导航栏区域，叠加会多让出一段。
            // 只做这一处收缩，不再额外叠加固定底部内边距 —— 固定内边距会紧贴键盘上沿形成一条不随内容滚动的色带
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .verticalScroll(rememberScrollState()),
    ) {
        // 标签读取期间不展示表单：空字段与「文件里就是空的」在界面上无法区分，易被误保存
        if (uiState.loading) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).height(28.dp),
                strokeWidth = 2.dp,
            )
            return@Column
        }
        val labels = tagFieldLabels()
        // 封面不套分区卡片：它是本页唯一需要看清的内容，卡片背景与内边距只会压缩它的可用面积
        MetadataCoverEditor(
            title = uiState.value(TagField.TITLE),
            artist = uiState.value(TagField.ARTIST),
            coverBytes = uiState.coverBytes,
            enabled = !uiState.saving,
            exporting = uiState.exportingCover,
            onPickCover = { coverLauncher.launch("image/*") },
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
        MetadataSection(title = stringResource(R.string.metadata_section_basic)) {
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
        MetadataSection(title = stringResource(R.string.metadata_section_replay_gain)) {
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
            onWholeModeToggle = onLyricsWholeModeToggle,
            onStartEdit = onEditStart,
            onEditDone = onEditEnd,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// 单个字段条目：把「取当前值 / 是否处于编辑态 / 回调」收在一处，避免各分组重复这套接线
@Composable
private fun FieldEntry(
    field: TagField,
    labels: Map<TagField, String>,
    uiState: MetadataUiState,
    onFieldChange: (TagField, String) -> Unit,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
) {
    // 星标与数值字段仍按文本编辑，但取值统一在此收口
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

// 评分条目：五颗星点选，点同一颗再次点击即清除评分
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

// 评分刻度：五颗星与容器内 POPM/rating 键的取值域一致
private const val RATING_MAX_STARS = 5

// 字段名文案：字段标识到界面文案的映射，供各分组统一取用
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
