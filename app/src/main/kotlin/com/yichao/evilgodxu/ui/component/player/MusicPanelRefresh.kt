package com.yichao.evilgodxu.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.api.sourceNameRes
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.DialogCard

/**
 * 「刷新封面」与「刷新歌词」共用的候选选择组合控件。
 *
 * 一致的部位（由本组件统一承载，两处不各自演化）：
 *  - 外层全屏蒙层 / 对话框容器样式与背景；
 *  - 内容区内边距、刷新状态（进度 / 空结果 / 候选列表）表现；
 *  - 底部双操作按钮的排列、尺寸与样式（含保存中状态）；
 *  - 顶部标题行（来源名 + 下拉切换 + 独立刷新按钮）。
 *
 * 不一致的部位（封面图片 vs 歌词文本）通过「内容视图注入」差异化：
 *  - `titleText` / `refreshLabel` / `noCandidatesText`：文案；
 *  - `candidateItem`：候选条目的内容形态；
 *  - `topBanner` / `footer`：蒙层顶部 / 对话框底部的附加信息槽位。
 *
 * 内容区以 `weight(1f, fill = false)` 承载，高度封顶、不抢占底部按钮的固定显示空间，
 * 避免候选内容撑开 / 压缩底部双按钮（悬浮窗为固定纵横比容器，内容过高时按钮会被挤扁）。
 */
@Composable
internal fun RefreshCandidateOverlay(
    visible: Boolean,
    searching: Boolean,
    saving: Boolean,
    candidates: List<NeteaseSongSearchResult>,
    selectedId: Long?,
    source: MusicSearchSource,
    titleText: String,
    refreshLabel: String,
    noCandidatesText: String,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    candidateItem: @Composable (NeteaseSongSearchResult, Boolean) -> Unit,
    topBanner: (@Composable BoxScope.() -> Unit)? = null,
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .97f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            )
            .pointerInput(Unit) { detectHorizontalDragGestures { _, amount -> if (amount > 50) onCancel() } },
        contentAlignment = Alignment.Center
    ) {
        topBanner?.invoke(this)
        RefreshCandidateContent(
            titleText = titleText,
            refreshLabel = refreshLabel,
            noCandidatesText = noCandidatesText,
            searching = searching,
            saving = saving,
            candidates = candidates,
            selectedId = selectedId,
            source = source,
            onSourceSelected = onSourceSelected,
            onRefresh = onRefresh,
            onCandidateSelected = onCandidateSelected,
            onConfirm = onConfirm,
            onCancel = onCancel,
            candidateItem = candidateItem,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        )
    }
}

@Composable
internal fun RefreshCandidateDialog(
    visible: Boolean,
    searching: Boolean,
    saving: Boolean,
    candidates: List<NeteaseSongSearchResult>,
    selectedId: Long?,
    source: MusicSearchSource,
    titleText: String,
    refreshLabel: String,
    noCandidatesText: String,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    candidateItem: @Composable (NeteaseSongSearchResult, Boolean) -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (!visible) return
    DialogCard(onDismiss = onCancel) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RefreshCandidateContent(
                titleText = titleText,
                refreshLabel = refreshLabel,
                noCandidatesText = noCandidatesText,
                searching = searching,
                saving = saving,
                candidates = candidates,
                selectedId = selectedId,
                source = source,
                onSourceSelected = onSourceSelected,
                onRefresh = onRefresh,
                onCandidateSelected = onCandidateSelected,
                onConfirm = onConfirm,
                onCancel = onCancel,
                candidateItem = candidateItem,
            )
            footer?.invoke(this)
        }
    }
}

// 刷新候选选择共享主体：标题行(点击切换来源+刷新按钮) + 候选/状态 + 底部双按钮
@Composable
internal fun RefreshCandidateContent(
    titleText: String,
    refreshLabel: String,
    noCandidatesText: String,
    searching: Boolean,
    saving: Boolean,
    candidates: List<NeteaseSongSearchResult>,
    selectedId: Long?,
    source: MusicSearchSource,
    onSourceSelected: (MusicSearchSource) -> Unit,
    onRefresh: () -> Unit,
    onCandidateSelected: (NeteaseSongSearchResult) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    candidateItem: @Composable (NeteaseSongSearchResult, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题行：居中显示当前来源名，点击弹出来源下拉列表，右侧独立刷新按钮
        Box(Modifier.fillMaxWidth()) {
            var sourceMenuExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !searching) { sourceMenuExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleText,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    )
                    Icon(
                        imageVector = AppIcons.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = sourceMenuExpanded,
                    onDismissRequest = { sourceMenuExpanded = false },
                ) {
                    MusicSearchSource.entries.forEach { src ->
                        DropdownMenuItem(
                            text = { Text(stringResource(src.sourceNameRes())) },
                            onClick = {
                                sourceMenuExpanded = false
                                onSourceSelected(src)
                            },
                            trailingIcon = {
                                if (src == source) {
                                    Icon(
                                        imageVector = AppIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = !searching,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = refreshLabel,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // 内容区：固定占位、可缩放（weight + 高度封顶），不抢占底部按钮固定显示空间
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            contentAlignment = Alignment.Center,
        ) {
            when {
                searching -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                candidates.isEmpty() -> Text(
                    text = noCandidatesText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        candidateItem(candidate, candidate.id == selectedId)
                    }
                }
            }
        }
        // 底部双按钮：排列与样式两处统一
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                onClick = onCancel,
            ) {
                Text(
                    text = stringResource(R.string.music_panel_rename_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selectedId != null && !saving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                onClick = { if (selectedId != null && !saving) onConfirm() },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.music_panel_rename_confirm),
                        color = if (saving) Color.Transparent else if (selectedId != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}
