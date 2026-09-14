package com.yichao.evilgodxu.ui.component.player

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.music.metadata.CoverExporter
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.metadata.isMediaStoreIndexed
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.LyricLayoutParams
import com.yichao.evilgodxu.data.settings.musicPanelLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.settingsFlow
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.data.music.panel.applyCoverCandidate
import com.yichao.evilgodxu.data.music.panel.applyLyricsCandidate
import com.yichao.evilgodxu.data.music.panel.performSearch
import com.yichao.evilgodxu.data.music.panel.playSearchResult
import com.yichao.evilgodxu.data.music.panel.searchCoverCandidates
import com.yichao.evilgodxu.data.music.panel.searchLyricsCandidates
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.theme.DarkColorScheme
import com.yichao.evilgodxu.theme.LightColorScheme
import com.yichao.evilgodxu.ui.component.player.ControlBar
import com.yichao.evilgodxu.ui.component.player.HeaderRow
import com.yichao.evilgodxu.ui.component.player.MusicErrorBanner
import com.yichao.evilgodxu.ui.component.player.PendingFeatureOverlay
import com.yichao.evilgodxu.ui.component.player.PlaylistOverlay
import com.yichao.evilgodxu.ui.component.player.ProgressSection
import com.yichao.evilgodxu.ui.component.player.applyLocalCover
import com.yichao.evilgodxu.ui.component.player.CoverRefreshOverlay
import com.yichao.evilgodxu.ui.component.player.CoverReplaceOverlay
import com.yichao.evilgodxu.ui.component.player.CurrentCover
import com.yichao.evilgodxu.ui.component.player.loadRecentCovers
import com.yichao.evilgodxu.ui.component.player.LocalCoverOverlay
import com.yichao.evilgodxu.ui.component.player.TrackInfo
import com.yichao.evilgodxu.ui.component.dialog.DeleteConfirmOverlay
import com.yichao.evilgodxu.ui.component.dialog.RenameOverlay
import com.yichao.evilgodxu.ui.component.dialog.SearchOverlay
import com.yichao.evilgodxu.ui.component.dialog.SearchResultsOverlay
import com.yichao.evilgodxu.ui.component.dialog.SettingsOverlay
import com.yichao.evilgodxu.ui.component.dialog.TimerOverlay
import com.yichao.evilgodxu.ui.component.player.LyricsPanel
import com.yichao.evilgodxu.ui.component.player.LyricsRefreshOverlay
import kotlinx.coroutines.launch

@Composable
fun MusicPanelOverlay(
    playbackState: MusicPlaybackState,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val metadataEnricher = LocalMetadataEnricher.current
    val playlistRefresher = LocalPlaylistRefresher.current

    val settings by context.settingsFlow().collectAsStateWithLifecycle(initialValue = null)
    // 音乐面板歌词排版：字号与可见行数独立可调
    val musicPanelLayout by context.musicPanelLyricLayoutFlow()
        .collectAsStateWithLifecycle(
            initialValue = LyricLayoutParams(
                LyricLayoutDefaults.MUSIC_PANEL_FONT_SIZE_SP,
                LyricLayoutDefaults.MUSIC_PANEL_VISIBLE_LINES,
            ),
        )
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settings?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemDark
    }
    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    val scope = rememberCoroutineScope()
    // 在组合阶段解析字符串资源，协程内无法调用 stringResource
    val lyricsRefreshFailedMessage = stringResource(R.string.music_panel_lyrics_refresh_failed)
    // 保存封面到相册的结果提示：成功与「无内嵌封面」走轻量提示，写入失败复用封面错误横幅
    val coverSavedMessage = stringResource(R.string.music_panel_save_cover_success)
    val coverNoEmbeddedMessage = stringResource(R.string.music_panel_save_cover_no_embedded)

    // 播放进度由 MusicPlaybackState 全局 ticker 驱动，此处不再独立轮询
    LaunchedEffect(playbackState.timerAutoStopped) {
        if (playbackState.timerAutoStopped) {
            playbackState.setTimerAutoStopped(false)
            onDismiss()
        }
    }

    var showPlaylist by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSoundEffects by remember { mutableStateOf(false) }
    val currentTrackId = playbackState.currentTrack?.id
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAudioSignalPath by remember { mutableStateOf(false) }
    var deleteTargetTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var renameIsTitle by remember { mutableStateOf(true) }
    var renameInitValue by remember { mutableStateOf("") }
    var showCoverRefresh by remember { mutableStateOf(false) }
    var showLocalCover by remember { mutableStateOf(false) }
    var selectedLocalCover by remember { mutableStateOf<RecentCover?>(null) }
    var showCoverReplace by remember { mutableStateOf(false) }
    var selectedCoverCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    var coverSaveFailed by remember { mutableStateOf(false) }
    var coverSaving by remember { mutableStateOf(false) }
    var showLyricsRefresh by remember { mutableStateOf(false) }
    var selectedLyricsCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    var coverTargetId by remember { mutableStateOf<Long?>(null) }
    var renameTargetId by remember { mutableStateOf<Long?>(null) }
    var lyricsTargetId by remember { mutableStateOf<Long?>(null) }

    MaterialTheme(colorScheme = colorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                        if (showLyricsRefresh) {
                            showLyricsRefresh = false
                            selectedLyricsCandidate = null
                            playbackState.setLyricsCandidates(emptyList())
                            playbackState.setLyricsRefreshError(null)
                        } else onDismiss()
                        true
                    } else false
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                            when {
                                showDeleteConfirm -> {
                                    showDeleteConfirm = false
                                    deleteTargetTrack = null
                                }
                                showPlaylist -> showPlaylist = false
                                showTimer -> showTimer = false
                                showSoundEffects -> showSoundEffects = false
                                showAudioSignalPath -> showAudioSignalPath = false
                                showSettings -> showSettings = false
                                showRename -> showRename = false
                            playbackState.showSearchResults -> {
                                playbackState.setSearchResultsVisible(false)
                            playbackState.setErrorMsg(null)
                        }
                        playbackState.isSearchMode -> {
                            playbackState.setSearchMode(false)
                            playbackState.setSearchResultsVisible(false)
                        }
                            else -> onDismiss()
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val cardBackground = if (isDarkTheme) {
                Color(0xFF161B22).copy(alpha = 0.72f)
            } else {
                Color(0xFFF5F5F7).copy(alpha = 0.82f)
            }
            val borderColor = if (isDarkTheme) {
                Color.White.copy(alpha = 0.06f)
            } else {
                Color.Black.copy(alpha = 0.06f)
            }

            Surface(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(0.92f)
                    .aspectRatio(4f / 3f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* 阻止点击穿透 */ }
                    ),
                shape = RoundedCornerShape(20.dp),
                color = cardBackground,
                border = BorderStroke(width = 1.dp, color = borderColor),
                shadowElevation = 0.dp
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    val designHeight = 285.dp
                    val scale = (maxHeight / designHeight).coerceAtMost(1f)

                    AnimatedContent(
                        targetState = playbackState.isSearchMode && !playbackState.showSearchResults,
                        transitionSpec = {
                            (slideInVertically { it } + fadeIn()).togetherWith(
                                slideOutVertically { it } + fadeOut()
                            )
                        },
                        label = "search_mode"
                    ) { showSearch ->
                        if (showSearch) {
                            SearchOverlay(
                                playbackState = playbackState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        transformOrigin = TransformOrigin(0.5f, 0f)
                                    )
                                    .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp)
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        transformOrigin = TransformOrigin(0.5f, 0f)
                                    )
                                    .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp)
                                    .pointerInput(showAudioSignalPath, showPlaylist, showTimer, showSettings) {
                                        var totalDx = 0f
                                        var totalDy = 0f
                                        detectDragGestures(
                                            onDragStart = { totalDx = 0f; totalDy = 0f },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                totalDx += dragAmount.x
                                                totalDy += dragAmount.y
                                            },
                                            onDragEnd = {
                                                if (!showPlaylist && !showTimer && !showSettings) {
                                                    when {
                                                        totalDy < -80f && kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx) -> showAudioSignalPath = true
                                                        totalDx > 50f && kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) -> playbackState.setSearchMode(true)
                                                        totalDx < -50f && kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) -> showSettings = true
                                                    }
                                                } else if (showAudioSignalPath && totalDy > 80f) {
                                                    showAudioSignalPath = false
                                                }
                                            }
                                        )
                                    }
                            ) {
                                HeaderRow(
                                    playbackState = playbackState,
                                    timerRemaining = playbackState.timerRemaining,
                                    onTimerClick = { showTimer = true },
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (playbackState.isLyricsVisible) {
                                        LyricsPanel(
                                            playbackState = playbackState,
                                            modifier = Modifier.fillMaxSize(),
                                            onClick = { playbackState.setLyricsVisible(false) },
                                            fontSize = musicPanelLayout.fontSizeSp.sp,
                                            visibleLines = musicPanelLayout.visibleLines,
                                        )
                                    } else {
                                        CurrentCover(
                                            track = playbackState.currentTrack,
                                            isPlaying = playbackState.isPlaying,
                                            onClick = { playbackState.setLyricsVisible(true) },
                                            onOnlineCover = {
                                                coverTargetId = playbackState.currentTrack?.id
                                                showCoverRefresh = true
                                                scope.launch { searchCoverCandidates(playbackState, playbackState.currentTrack!!, playbackState.coverRefreshSource) }
                                            },
                                            onLocalCover = {
                                                coverTargetId = playbackState.currentTrack?.id
                                                selectedLocalCover = null
                                                showLocalCover = true
                                                scope.launch { playbackState.setLocalCoverCandidates(loadRecentCovers(context)) }
                                            },
                                            // 保存内嵌封面原图到系统相册：不改动曲目元数据，故无目标校验；
                                            // 无内嵌封面时以提示告知，写入失败仍复用封面错误横幅
                                            onSaveCover = {
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
                                            }
                                        )
                                    }
                                }
                                if (!playbackState.isLyricsVisible) {
                                    TrackInfo(
                                        playbackState = playbackState,
                                        onClick = { playbackState.setLyricsVisible(true) },
                                        onRenameRequest = { isTitle, text ->
                                            renameIsTitle = isTitle
                                            renameInitValue = text
                                            renameTargetId = playbackState.currentTrack?.id
                                            showRename = true
                                        }
                                    )
                                }
                                ProgressSection(playbackState = playbackState)

                                ControlBar(
                                    playbackState = playbackState,
                                    onPlaylistClick = { showPlaylist = true },
                                    onLyricsRefreshClick = {
                                        lyricsTargetId = playbackState.currentTrack?.id
                                        showLyricsRefresh = true
                                        playbackState.currentTrack?.let { track -> scope.launch { searchLyricsCandidates(playbackState, track, playbackState.lyricsRefreshSource) } }
                                    }
                                )
                            }
                        }
                    }

                    PlaylistOverlay(
                        visible = showPlaylist,
                        playbackState = playbackState,
                        onScan = onScan,
                        onTrackSelected = { index ->
                            scope.launch {
                                playTrackAt(context, playbackState, index)
                            }
                            showPlaylist = false
                        },
                        onTrackLongPress = { track ->
                            deleteTargetTrack = track
                            showDeleteConfirm = true
                        },
                        onDismiss = { showPlaylist = false }
                    )

                    TimerOverlay(
                        visible = showTimer,
                        minutes = playbackState.timerMinutes,
                        onMinutesChange = { playbackState.setTimerMinutes(it) },
                        onConfirm = {
                            playbackState.startTimer(playbackState.timerMinutes)
                            showTimer = false
                        },
                        onCancel = { showTimer = false }
                    )

                    SearchResultsOverlay(
                        visible = playbackState.showSearchResults,
                        playbackState = playbackState,
                        context = context,
                        onClose = {
                            playbackState.setSearchResultsVisible(false)
                            playbackState.setErrorMsg(null)
                        },
                        onRefresh = {
                            scope.launch {
                                performSearch(playbackState, context)
                            }
                        },
                        onTrackSelected = { result ->
                            scope.launch {
                                playSearchResult(
                                    result, playbackState, context, scope,
                                    metadataEnricher, playlistRefresher,
                                )
                            }
                        }
                    )

                    DeleteConfirmOverlay(
                        visible = showDeleteConfirm,
                        track = deleteTargetTrack,
                        onConfirm = {
                            deleteTargetTrack?.let { track ->
                                scope.launch { playbackState.deleteSongPermanently(context, track) }
                            }
                            showDeleteConfirm = false
                            deleteTargetTrack = null
                        },
                        onCancel = {
                            showDeleteConfirm = false
                            deleteTargetTrack = null
                        }
                    )

                    RenameOverlay(
                        visible = showRename,
                        isTitle = renameIsTitle,
                        initialValue = renameInitValue,
                        onConfirm = { newValue ->
                            showRename = false
                            val track = playbackState.currentTrack
                            if (track != null && track.id == renameTargetId) {
                                val updated = if (renameIsTitle) track.copy(title = newValue)
                                              else track.copy(artist = newValue)
                                playbackState.renameTrackMetadata(updated)
                                // 手动重命名标题/艺术家后写入音频文件元数据
                                scope.launch {
                                    MusicMetadataWriter.writeTitleArtist(context, track, updated.title, updated.artist)
                                }
                            }
                        },
                        onCancel = { showRename = false }
                    )

                    SettingsOverlay(
                        visible = showSettings,
                        showSoundEffects = showSoundEffects,
                        onShowSoundEffectsChange = { showSoundEffects = it },
                        onDismiss = { showSettings = false }
                    )

                    LocalCoverOverlay(
                        visible = showLocalCover,
                        playbackState = playbackState,
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
                        }
                    )

                    CoverRefreshOverlay(
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
                                scope.launch { searchCoverCandidates(playbackState, track, source) }
                            }
                        },
                        onRefresh = {
                            val track = playbackState.currentTrack
                            if (track != null && track.id == coverTargetId) {
                                selectedCoverCandidate = null
                                scope.launch { searchCoverCandidates(playbackState, track, playbackState.coverRefreshSource) }
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
                        }
                    )

                    LyricsRefreshOverlay(
                        visible = showLyricsRefresh,
                        track = playbackState.currentTrack,
                        playbackState = playbackState,
                        selectedId = selectedLyricsCandidate?.id,
                        context = context,
                        onCandidateSelected = { selectedLyricsCandidate = it },
                        onSourceSelected = { source ->
                            val track = playbackState.currentTrack
                            if (track != null && track.id == lyricsTargetId && source != playbackState.lyricsRefreshSource) {
                                playbackState.setLyricsRefreshSource(source)
                                selectedLyricsCandidate = null
                                scope.launch { searchLyricsCandidates(playbackState, track, source) }
                            }
                        },
                        onRefresh = {
                            val track = playbackState.currentTrack
                            if (track != null && track.id == lyricsTargetId) {
                                selectedLyricsCandidate = null
                                scope.launch { searchLyricsCandidates(playbackState, track, playbackState.lyricsRefreshSource) }
                            }
                        },
                        onConfirm = {
                            val candidate = selectedLyricsCandidate
                            val track = playbackState.currentTrack
                            if (candidate != null && track != null && track.id == lyricsTargetId) scope.launch {
                                val success = applyLyricsCandidate(context, playbackState, track, candidate)
                                if (success) {
                                    showLyricsRefresh = false
                                    selectedLyricsCandidate = null
                                    playbackState.setLyricsCandidates(emptyList())
                                } else {
                                    playbackState.setLyricsRefreshError(lyricsRefreshFailedMessage)
                                }
                            }
                        },
                        onCancel = {
                            showLyricsRefresh = false
                            selectedLyricsCandidate = null
                            playbackState.setLyricsCandidates(emptyList())
                            playbackState.setLyricsRefreshError(null)
                        }
                    )

                    CoverReplaceOverlay(
                        visible = showCoverReplace,
                        track = playbackState.currentTrack,
                        candidate = selectedCoverCandidate,
                        saving = coverSaving,
                        onConfirm = {
                            val candidate = selectedCoverCandidate ?: return@CoverReplaceOverlay
                            val track = playbackState.currentTrack ?: return@CoverReplaceOverlay
                            if (track.id != coverTargetId) return@CoverReplaceOverlay
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
                        onCancel = { showCoverReplace = false }
                    )
                    if (coverSaveFailed) {
                        MusicErrorBanner(
                            message = stringResource(R.string.music_panel_cover_save_failed),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            onDismiss = { coverSaveFailed = false }
                        )
                    }

                    PendingFeatureOverlay(
                        visible = showAudioSignalPath,
                        onDismiss = { showAudioSignalPath = false },
                    )
                }
            }
        }
    }
}
