package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.LocalProxySourceStore
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformRegistry
import com.yichao.evilgodxu.ui.component.player.CoverCandidateItem
import com.yichao.evilgodxu.ui.component.player.RefreshCandidateDialog

/**
 * 在线补全元数据的候选对话框：与「刷新封面 / 刷新歌词」同一个控件、同一种候选条目，
 * 差异只在确认后多问一步 —— 补全方式（仅补全 / 全覆盖）放在二级对话框里，
 * 不往候选列表底下塞选择器，免得一屏里叠两套决策。
 *
 * 候选与来源状态由 [MusicPlaybackState] 持有：搜索在调用方的状态持有者里发起，
 * 对话框只负责展示与回传选择 —— 元数据编辑页与专辑信息栏因此共用同一个入口，
 * 两处的候选、来源与刷新行为天然一致。
 */
@Composable
internal fun MetadataFillDialog(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    selectedId: Long?,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onReload: () -> Unit,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val proxySources by LocalProxySourceStore.current.sources.collectAsStateWithLifecycle()
    RefreshCandidateDialog(
        visible = visible,
        searching = playbackState.isMetadataFillSearching,
        // 写入不在本对话框发生：确认候选后交给二级对话框，故这里不存在保存中状态
        saving = false,
        candidates = playbackState.metadataFillCandidates,
        selectedId = selectedId,
        source = playbackState.metadataFillSource,
        titleText = OnlinePlatformRegistry.displayName(context, proxySources, playbackState.metadataFillSource),
        refreshLabel = stringResource(R.string.metadata_fill_refresh),
        noCandidatesText = stringResource(R.string.metadata_fill_no_candidates),
        onSourceSelected = onSourceSelected,
        onRefresh = onReload,
        onCandidateSelected = onCandidateSelected,
        onConfirm = onConfirm,
        onCancel = onCancel,
        candidateItem = { candidate, selected ->
            CoverCandidateItem(candidate = candidate, selected = selected, onSelected = onCandidateSelected)
        },
    )
}

/**
 * 二级对话框：确认候选之后选择补全方式。
 *
 * 默认停在「仅补全」——它不动用户已经填过的内容；要换掉旧值必须显式切到「全覆盖」。
 * 这是补全唯一的破坏性分支，值得多问一次。
 */
@Composable
internal fun FillModeDialog(
    visible: Boolean,
    overwrite: Boolean,
    applying: Boolean,
    onOverwriteChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.metadata_fill_mode_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            FillModeOption(
                label = stringResource(R.string.metadata_fill_mode_merge),
                hint = stringResource(R.string.metadata_fill_mode_merge_hint),
                selected = !overwrite,
                enabled = !applying,
                onClick = { onOverwriteChange(false) },
            )
            FillModeOption(
                label = stringResource(R.string.metadata_fill_mode_overwrite),
                hint = stringResource(R.string.metadata_fill_mode_overwrite_hint),
                selected = overwrite,
                enabled = !applying,
                onClick = { onOverwriteChange(true) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { if (!applying) onConfirm() },
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_rename_confirm),
                            color = if (applying) Color.Transparent else MaterialTheme.colorScheme.onPrimary,
                        )
                        if (applying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// 补全方式的单选项：标题 + 一句说明，选中态以主题色淡染区分
@Composable
private fun FillModeOption(
    label: String,
    hint: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.16f else 0.06f),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            )
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}
