package com.yichao.evilgodxu.screens.home.component.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.download.upgradeTrackToLossless
import com.yichao.evilgodxu.data.music.panel.searchLosslessUpgradeCandidates
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.component.rememberOnlinePlatformOptions
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 无损升级对话框（与刷新封面/歌词同风格）：按来源搜索在线原曲，展示封面/标题/艺术家供用户确认，
// 确认后下载该候选的无损版本替换本地文件；升级中不可关闭，失败时保留供重试
@Composable
internal fun LosslessUpgradeDialog(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val track = playbackState.currentTrack
    var selectedCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    val upgradeFailedMessage = stringResource(R.string.home_upgrade_lossless_failed)

    // 打开且曲目/来源变化时自动搜索候选
    LaunchedEffect(visible, track?.id, playbackState.losslessUpgradeSource) {
        if (visible && track != null) {
            selectedCandidate = null
            searchLosslessUpgradeCandidates(context, playbackState, track, playbackState.losslessUpgradeSource)
        }
    }

    if (!visible || track == null) return
    DialogCard(onDismiss = { if (!playbackState.losslessUpgradeBusy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题行：居中显示当前来源名，点击弹出来源下拉列表，右侧独立刷新按钮
            Box(Modifier.fillMaxWidth()) {
                var sourceMenuExpanded by remember { mutableStateOf(false) }
                val platformOptions = rememberOnlinePlatformOptions()
                // 平台展示名：候选里查不到当前平台时（平台已随音源移除）回退平台键
                val currentPlatformName = platformOptions.firstOrNull { it.source == playbackState.losslessUpgradeSource }?.name
                    ?: playbackState.losslessUpgradeSource.key
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !playbackState.isLosslessUpgradeSearching && !playbackState.losslessUpgradeBusy) {
                            sourceMenuExpanded = true
                        }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentPlatformName,
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
                        platformOptions.forEach { platform ->
                            DropdownMenuItem(
                                text = { Text(platform.name) },
                                onClick = {
                                    sourceMenuExpanded = false
                                    if (platform.source != playbackState.losslessUpgradeSource) {
                                        playbackState.losslessUpgradeSource = platform.source
                                    }
                                },
                                trailingIcon = {
                                    if (platform.source == playbackState.losslessUpgradeSource) {
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
                    onClick = {
                        selectedCandidate = null
                        scope.launch {
                            searchLosslessUpgradeCandidates(context, playbackState, track, playbackState.losslessUpgradeSource)
                        }
                    },
                    enabled = !playbackState.isLosslessUpgradeSearching && !playbackState.losslessUpgradeBusy,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = stringResource(R.string.home_upgrade_lossless_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // 候选或状态：展示封面/标题/艺术家，点击选中
            when {
                playbackState.isLosslessUpgradeSearching -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                playbackState.losslessUpgradeCandidates.isEmpty() -> {
                    Text(
                        stringResource(R.string.home_upgrade_lossless_no_candidates),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(playbackState.losslessUpgradeCandidates, key = { it.id }) { candidate ->
                        val selected = candidate.id == selectedCandidate?.id
                        Column(
                            modifier = Modifier
                                .width(112.dp)
                                .clickable { if (!playbackState.losslessUpgradeBusy) selectedCandidate = candidate },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = if (selected) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else null,
                            ) {
                                val coverUrl = candidate.coverUrl?.takeIf { it.isNotBlank() }
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (coverUrl != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(coverUrl)
                                                .diskCachePolicy(CachePolicy.DISABLED)
                                                .build(),
                                            contentDescription = candidate.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        Icon(
                                            imageVector = AppIcons.MusicNote,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = candidate.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = candidate.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // 最近一次升级失败提示
            playbackState.losslessUpgradeError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 取消 / 升级按钮：升级中禁用并显示进度
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                    onClick = { if (!playbackState.losslessUpgradeBusy) onDismiss() },
                ) {
                    Text(
                        stringResource(R.string.home_upgrade_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedCandidate != null && !playbackState.losslessUpgradeBusy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    onClick = {
                        val candidate = selectedCandidate
                        if (candidate != null && !playbackState.losslessUpgradeBusy) {
                            scope.launch {
                                playbackState.losslessUpgradeBusy = true
                                playbackState.losslessUpgradeError = null
                                val success = upgradeTrackToLossless(context, playbackState, track, candidate)
                                playbackState.losslessUpgradeBusy = false
                                if (success) {
                                    playbackState.losslessUpgradeError = null
                                    playbackState.losslessUpgradeCandidates = emptyList()
                                    onDismiss()
                                } else {
                                    playbackState.losslessUpgradeError = upgradeFailedMessage
                                }
                            }
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.home_upgrade_lossless_confirm),
                            color = if (playbackState.losslessUpgradeBusy) {
                                Color.Transparent
                            } else if (selectedCandidate != null) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (playbackState.losslessUpgradeBusy) {
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
}
