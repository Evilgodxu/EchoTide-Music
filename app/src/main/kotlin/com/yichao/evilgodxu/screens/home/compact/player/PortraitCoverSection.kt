package com.yichao.evilgodxu.screens.home.compact.player

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.CoverExporter
import com.yichao.evilgodxu.data.music.metadata.isMediaStoreIndexed
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.music.panel.applyCoverCandidate
import com.yichao.evilgodxu.data.music.panel.searchCoverCandidates
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.permission.hasImageAccess
import com.yichao.evilgodxu.permission.mediaImagePermission
import com.yichao.evilgodxu.screens.home.component.player.HomeImmersiveCover
import com.yichao.evilgodxu.ui.component.player.CoverContextMenu
import com.yichao.evilgodxu.ui.component.player.CoverRefreshDialog
import com.yichao.evilgodxu.ui.component.player.CoverReplaceDialog
import com.yichao.evilgodxu.ui.component.player.LocalCoverDialog
import com.yichao.evilgodxu.ui.component.player.MusicErrorBanner
import com.yichao.evilgodxu.ui.component.player.applyLocalCover
import com.yichao.evilgodxu.ui.component.player.loadRecentCovers
import kotlinx.coroutines.launch

// 竖屏沉浸封面：长按打开封面菜单，在线刷新、本地选择、保存原图与替换流程在此闭环。
// 顶部的保存失败横幅与封面同域，避免把封面操作状态散回播放器主布局
@Composable
internal fun PortraitCoverSection(
    playbackState: MusicPlaybackState,
    topBarInset: Dp,
    swipePreviewText: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coverSavedMessage = stringResource(R.string.music_panel_save_cover_success)
    val coverNoEmbeddedMessage = stringResource(R.string.music_panel_save_cover_no_embedded)
    var showCoverMenu by remember { mutableStateOf(false) }
    var showCoverRefresh by remember { mutableStateOf(false) }
    var showLocalCover by remember { mutableStateOf(false) }
    var showCoverReplace by remember { mutableStateOf(false) }
    var selectedCoverCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    var selectedLocalCover by remember { mutableStateOf<RecentCover?>(null) }
    var coverSaving by remember { mutableStateOf(false) }
    var coverSaveFailed by remember { mutableStateOf(false) }
    var coverTargetId by remember { mutableStateOf<Long?>(null) }
    // 本地封面候选取自系统图片库：缺图片访问时先申请，授权后直接重载候选
    val localCoverPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scope.launch { playbackState.setLocalCoverCandidates(loadRecentCovers(context)) }
    }

    Box(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = { if (playbackState.currentTrack != null) showCoverMenu = true },
        ),
    ) {
        HomeImmersiveCover(
            track = playbackState.currentTrack,
            kind = playbackState.lastSwitchKind,
            modifier = Modifier.fillMaxSize(),
        )
        // 长按菜单锚定封面，显示在封面底部
        CoverContextMenu(
            visible = showCoverMenu,
            onOnlineCover = {
                showCoverMenu = false
                coverTargetId = playbackState.currentTrack?.id
                showCoverRefresh = true
                playbackState.currentTrack?.let { track ->
                    scope.launch { searchCoverCandidates(context, playbackState, track, playbackState.coverRefreshSource) }
                }
            },
            onLocalCover = {
                showCoverMenu = false
                coverTargetId = playbackState.currentTrack?.id
                selectedLocalCover = null
                showLocalCover = true
                if (hasImageAccess(context)) {
                    scope.launch { playbackState.setLocalCoverCandidates(loadRecentCovers(context)) }
                } else {
                    localCoverPermissionLauncher.launch(mediaImagePermission())
                }
            },
            // 保存内嵌封面原图到系统相册：不改动曲目元数据，故无目标校验；
            // 无内嵌封面时以提示告知，写入失败仍复用封面错误横幅
            onSaveCover = {
                showCoverMenu = false
                playbackState.currentTrack?.let { track ->
                    scope.launch {
                        val message = when (CoverExporter.export(context, track)) {
                            CoverExporter.Result.Saved -> coverSavedMessage
                            CoverExporter.Result.NoEmbeddedCover -> coverNoEmbeddedMessage
                            CoverExporter.Result.Failed -> null
                        }
                        if (message != null) {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        } else {
                            coverSaveFailed = true
                        }
                    }
                }
            },
            onDismiss = { showCoverMenu = false },
        )
        // 纵向切歌预览：滑动未松手时显示于封面底部，提醒将播放的曲目方向。
        // 显式限定到顶层重载：此处同时处于 Column 与 Box 两个作用域，不加限定会解析到
        // ColumnScope 版本，而该版本无法在本层（Box 内）用隐式接收者调用
        androidx.compose.animation.AnimatedVisibility(
            visible = swipePreviewText != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(150)) +
                slideInVertically(animationSpec = tween(150)) { it / 2 },
            exit = fadeOut(animationSpec = tween(150)) +
                slideOutVertically(animationSpec = tween(150)) { it / 2 },
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = swipePreviewText.orEmpty(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        LocalCoverDialog(
            visible = showLocalCover,
            candidates = playbackState.localCoverCandidates,
            selected = selectedLocalCover,
            saving = coverSaving,
            onSelected = { selectedLocalCover = it },
            onConfirm = {
                val cover = selectedLocalCover
                val track = playbackState.currentTrack
                if (cover != null && track != null && track.id == coverTargetId) {
                    coverSaving = true
                    coverSaveFailed = false
                    scope.launch {
                        if (applyLocalCover(context, playbackState, track, cover)) {
                            showLocalCover = false
                            selectedLocalCover = null
                        } else {
                            coverSaveFailed = true
                        }
                        coverSaving = false
                    }
                }
            },
            onCancel = {
                showLocalCover = false
                selectedLocalCover = null
                playbackState.setLocalCoverCandidates(emptyList())
            },
        )

        CoverRefreshDialog(
            visible = showCoverRefresh && !showCoverReplace && !showLocalCover,
            track = playbackState.currentTrack,
            playbackState = playbackState,
            context = context,
            selectedId = selectedCoverCandidate?.id,
            saving = coverSaving,
            onCandidateSelected = { selectedCoverCandidate = it },
            onSourceSelected = { source ->
                val track = playbackState.currentTrack
                if (track != null && track.id == coverTargetId && source != playbackState.coverRefreshSource) {
                    playbackState.setCoverRefreshSource(source)
                    selectedCoverCandidate = null
                    scope.launch { searchCoverCandidates(context, playbackState, track, source) }
                }
            },
            onRefresh = {
                val track = playbackState.currentTrack
                if (track != null && track.id == coverTargetId) {
                    selectedCoverCandidate = null
                    scope.launch { searchCoverCandidates(context, playbackState, track, playbackState.coverRefreshSource) }
                }
            },
            onConfirm = {
                val candidate = selectedCoverCandidate
                val track = playbackState.currentTrack
                if (candidate != null && track != null && track.id == coverTargetId) {
                    // 索引曲目的封面由系统维护，已有封面时确认是否覆盖
                    val hasCover = track.isMediaStoreIndexed
                    if (hasCover) {
                        showCoverReplace = true
                    } else {
                        coverSaving = true
                        coverSaveFailed = false
                        scope.launch {
                            coverSaveFailed = !applyCoverCandidate(context, playbackState, track, candidate)
                            if (!coverSaveFailed) {
                                showCoverRefresh = false
                                selectedCoverCandidate = null
                            }
                            coverSaving = false
                        }
                    }
                }
            },
            onCancel = {
                showCoverRefresh = false
                selectedCoverCandidate = null
                playbackState.setCoverCandidates(emptyList())
            },
        )

        CoverReplaceDialog(
            visible = showCoverReplace,
            track = playbackState.currentTrack,
            candidate = selectedCoverCandidate,
            saving = coverSaving,
            onConfirm = {
                val candidate = selectedCoverCandidate ?: return@CoverReplaceDialog
                val track = playbackState.currentTrack ?: return@CoverReplaceDialog
                if (track.id != coverTargetId) return@CoverReplaceDialog
                coverSaving = true
                coverSaveFailed = false
                scope.launch {
                    coverSaveFailed = !applyCoverCandidate(context, playbackState, track, candidate)
                    if (!coverSaveFailed) {
                        showCoverReplace = false
                        showCoverRefresh = false
                        selectedCoverCandidate = null
                    }
                    coverSaving = false
                }
            },
            onCancel = { showCoverReplace = false },
        )

        if (coverSaveFailed) {
            MusicErrorBanner(
                message = stringResource(R.string.music_panel_cover_save_failed),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = topBarInset + 10.dp, end = 16.dp),
                onDismiss = { coverSaveFailed = false },
            )
        }
    }
}
