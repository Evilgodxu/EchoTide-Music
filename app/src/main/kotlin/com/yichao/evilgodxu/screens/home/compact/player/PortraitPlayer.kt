package com.yichao.evilgodxu.screens.home.compact.player

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.music.metadata.CoverExporter
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.isMediaStoreIndexed
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.settings.homePortraitLyricLayoutFlow
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.LyricLayoutParams
import com.yichao.evilgodxu.data.music.panel.applyCoverCandidate
import com.yichao.evilgodxu.data.music.panel.applyLocalLyrics
import com.yichao.evilgodxu.data.music.panel.applyLyricsCandidate
import com.yichao.evilgodxu.data.music.panel.applyLyricsLineEdit
import com.yichao.evilgodxu.data.music.panel.searchCoverCandidates
import com.yichao.evilgodxu.data.music.panel.searchLyricsCandidates
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.component.dialog.LosslessUpgradeDialog
import com.yichao.evilgodxu.screens.home.component.player.HomeImmersiveCover
import com.yichao.evilgodxu.screens.home.component.player.MarqueeInfoLine
import com.yichao.evilgodxu.screens.home.component.player.PlayerControls
import com.yichao.evilgodxu.screens.home.component.analysis.LibraryAnalysisController
import com.yichao.evilgodxu.screens.home.component.analysis.LibraryAnalysisSheet
import com.yichao.evilgodxu.screens.home.component.queue.PlaylistSheet
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.currentTrackNeedsLosslessUpgrade
import com.yichao.evilgodxu.ui.component.MusicErrorBanner
import com.yichao.evilgodxu.ui.component.ProgressSection
import com.yichao.evilgodxu.ui.component.applyLocalCover
import com.yichao.evilgodxu.ui.component.CoverContextMenu
import com.yichao.evilgodxu.ui.component.CoverRefreshDialog
import com.yichao.evilgodxu.ui.component.CoverReplaceDialog
import com.yichao.evilgodxu.ui.component.loadRecentCovers
import com.yichao.evilgodxu.ui.component.LocalCoverDialog
import com.yichao.evilgodxu.ui.component.menuEdgePositionProvider
import com.yichao.evilgodxu.ui.component.MiniContextMenu
import com.yichao.evilgodxu.ui.component.dialog.RenameDialog
import com.yichao.evilgodxu.ui.component.LyricsEditDialog
import com.yichao.evilgodxu.ui.component.LyricsPanel
import com.yichao.evilgodxu.ui.component.LyricsRefreshDialog
import com.yichao.evilgodxu.ui.copyToClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yichao.evilgodxu.LocalMusicPanelStateHolder

// 竖屏播放器主体：沉浸封面 + 歌词 + 标题与艺术家 + 底部控制栏
@Composable
internal fun PortraitPlayer(
    modifier: Modifier = Modifier,
    // 标题栏区域高度：封面顶部渐隐区与错误横幅避让基准
    topBarInset: Dp = 0.dp,
    // 纵向切歌预览提示：滑动未松手时显示于专辑封面底部
    swipePreviewText: String? = null,
    // 曲库分析会话：状态与后台分析任务常驻首页层
    libraryAnalysis: LibraryAnalysisController,
    // 播放列表面板显隐：由首页层持有，显示期间禁用上下滑动切歌
    playlistVisible: Boolean,
    onPlaylistVisibilityChange: (Boolean) -> Unit,
    // 长按上一曲/下一曲唤出调速对话框：弹窗宿主在首页对话框层
    onSpeedLongClick: () -> Unit,
    onOpenOnlineSearch: (String) -> Unit = {},
) {
    val playbackState = LocalMusicPanelStateHolder.current.state

    // 播放列表与曲库分析展开时，系统返回键收起面板（曲库分析关闭不中断后台任务）
    BackHandler(enabled = playlistVisible || libraryAnalysis.visible) {
        if (libraryAnalysis.visible) libraryAnalysis.dismiss() else onPlaylistVisibilityChange(false)
    }

    // 播放进度由 MusicPlaybackState 全局 ticker 驱动，此处不再独立轮询

    // 首页竖屏歌词排版：字号与可见行数独立可调
    val context = LocalContext.current
    val homePortraitLayout by context.homePortraitLyricLayoutFlow()
        .collectAsStateWithLifecycle(
            initialValue = LyricLayoutParams(
                LyricLayoutDefaults.HOME_PORTRAIT_FONT_SIZE_SP,
                LyricLayoutDefaults.HOME_PORTRAIT_VISIBLE_LINES,
            ),
        )

    // 歌词区高度随可见行数与字号自适应：每行占行高与上下 2dp 内边距，行间 1dp 固定间距，
    // 末尾补足歌词面板顶部 4dp 内边距，使外层高度与内部歌词窗口一致。
    // 行高必须与 LyricsPanel/LyricChar 同口径（沿用 LocalTextStyle 的 24sp 行高），否则外层偏矮裁剪歌词
    val textMeasurer = rememberTextMeasurer()
    val lyricTextStyle = LocalTextStyle.current
    val lyricLineHeight = with(LocalDensity.current) {
        textMeasurer.measure(
            AnnotatedString("歌词"),
            lyricTextStyle.merge(TextStyle(fontSize = homePortraitLayout.fontSizeSp.sp)),
        ).size.height.toDp()
    }
    val lyricsAreaHeight = (lyricLineHeight + 4.dp) * homePortraitLayout.visibleLines +
        1.dp * (homePortraitLayout.visibleLines - 1) + 4.dp

    // 长按功能状态：复用音乐面板的封面/歌词刷新与标题/艺人重命名能力
    val scope = rememberCoroutineScope()
    val lyricsRefreshFailedMessage = stringResource(R.string.music_panel_lyrics_refresh_failed)
    // 保存封面到相册的结果提示：成功与「无内嵌封面」走轻量提示，写入失败复用封面错误横幅
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
    var showLyricsRefresh by remember { mutableStateOf(false) }
    var selectedLyricsCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    var lyricsTargetId by remember { mutableStateOf<Long?>(null) }
    // 歌词长按菜单与本地歌词导入状态
    var showLyricsMenu by remember { mutableStateOf(false) }
    var lyricsImportFailed by remember { mutableStateOf(false) }
    // 歌词原文编辑对话框状态：目标行为打开编辑时定位的当前演唱行
    var showLyricsEdit by remember { mutableStateOf(false) }
    var lyricsEditIndex by remember { mutableIntStateOf(0) }
    var lyricsEditInitialText by remember { mutableStateOf("") }
    var lyricsEditFailed by remember { mutableStateOf(false) }
    // 长按歌词时定格的播放位置，编辑落点据此定位，避免菜单操作期间播放推进导致错行
    var lyricsMenuPositionMs by remember { mutableLongStateOf(0L) }
    val lyricsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val track = playbackState.currentTrack
            if (track != null) {
                scope.launch {
                    lyricsImportFailed = !applyLocalLyrics(context, playbackState, track, uri)
                }
            }
        }
    }
    var showRename by remember { mutableStateOf(false) }
    var renameIsTitle by remember { mutableStateOf(true) }
    var renameInitValue by remember { mutableStateOf("") }
    var renameTargetId by remember { mutableStateOf<Long?>(null) }
    var showMetaMenu by remember { mutableStateOf(false) }
    var menuText by remember { mutableStateOf("") }
    var menuIsTitle by remember { mutableStateOf(true) }
    // 无损升级确认对话框显隐
    var showLosslessUpgrade by remember { mutableStateOf(false) }
    // 歌词微调按钮显示状态：点击歌词区切换
    var lyricTuneVisible by remember { mutableStateOf(false) }
    // 微调操作计数：每次调整自增以重置自动隐藏计时
    var tuneVersion by remember { mutableIntStateOf(0) }
    // 浮动提示：显示当前微调的毫秒数
    var tuneHintText by remember { mutableStateOf("") }
    var tuneHintVersion by remember { mutableIntStateOf(0) }
    // 显示后 2 秒无操作自动隐藏
    LaunchedEffect(lyricTuneVisible, tuneVersion) {
        if (lyricTuneVisible) {
            delay(2000)
            lyricTuneVisible = false
        }
    }
    // 浮动提示暂显后自动消失
    LaunchedEffect(tuneHintVersion) {
        if (tuneHintText.isNotEmpty()) {
            delay(800)
            tuneHintText = ""
        }
    }

    // 多窗口（自由窗口/分屏）检测：小窗底部有系统控制条，需预留半高控制栏的避让留白
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    val bottomClearance = if (activity?.isInMultiWindowMode() == true) {
        BottomControlBarClearance
    } else {
        0.dp
    }

    // 外层容器：沉浸封面置顶，其余模块从封面下方按序排列
    BoxWithConstraints(modifier = modifier) {
        // 封面保持全宽沉浸：常规窗口下为宽高等于宽度的正方形；自由窗口高度偏矮时按可用高度压缩高度，
        // 配合内容裁切保留全宽置顶的沉浸效果，避免底部内容被截断
        val coverHeight = (maxHeight - lyricsAreaHeight - BottomFixedContentHeight - bottomClearance)
            .coerceAtLeast(MinCoverHeight)
            .coerceAtMost(maxWidth)
        // 沉浸式专辑封面：全宽置顶并嵌入标题栏区域，上下边缘渐隐为透明融入背景
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(coverHeight)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { if (playbackState.currentTrack != null) showCoverMenu = true },
                ),
        ) {
            HomeImmersiveCover(
                track = playbackState.currentTrack,
                topFraction = (topBarInset + TopFadeExtra) / coverHeight,
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
                        scope.launch { searchCoverCandidates(playbackState, track, playbackState.coverRefreshSource) }
                    }
                },
                onLocalCover = {
                    showCoverMenu = false
                    coverTargetId = playbackState.currentTrack?.id
                    selectedLocalCover = null
                    showLocalCover = true
                    scope.launch { playbackState.setLocalCoverCandidates(loadRecentCovers(context)) }
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
            // 纵向切歌预览：滑动未松手时显示于封面底部，提醒将播放的曲目方向
            AnimatedVisibility(
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
        }
        // 歌词/标题/进度/控制栏：从封面下方按序排列
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = coverHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomClearance),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 封面与歌词间距
                Spacer(Modifier.height(8.dp))
                // 歌词：高度随设置的可见行数自适应（默认 5 行），点击歌词区切换微调按钮显隐
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(lyricsAreaHeight),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (playbackState.currentTrack != null) {
                            LyricsPanel(
                                playbackState = playbackState,
                                onClick = { lyricTuneVisible = !lyricTuneVisible },
                                onLongClick = {
                                    if (playbackState.currentTrack != null) {
                                        // 长按瞬间定格播放位置，作为歌词编辑的目标行依据
                                        lyricsMenuPositionMs = playbackState.currentPosition
                                        showLyricsMenu = true
                                    }
                                },
                                fontSize = homePortraitLayout.fontSizeSp.sp,
                                visibleLines = homePortraitLayout.visibleLines,
                                contentColor = Color.White,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.home_player_empty),
                                fontSize = 14.sp,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                        }
                    }
                    LyricsContextMenu(
                        visible = showLyricsMenu,
                        editEnabled = playbackState.currentTrack?.lyricLines?.isNotEmpty() == true,
                        onEdit = {
                            showLyricsMenu = false
                            val track = playbackState.currentTrack
                            if (track?.lyricLines?.isNotEmpty() == true) {
                                // 按长按定格位置定位歌词行，与长按瞬间屏幕显示的当前行一致
                                val index = track.lyricLines
                                    .indexOfLast { it.timeMs <= lyricsMenuPositionMs }
                                    .coerceAtLeast(0)
                                lyricsEditIndex = index
                                // 预填该行存储的完整原文：时间戳 + 歌词（含逐字标签） + 翻译行
                                lyricsEditInitialText = MusicMetadataCache.encodeLyrics(listOf(track.lyricLines[index]))
                                showLyricsEdit = true
                            }
                        },
                        onOnlineSearch = {
                            showLyricsMenu = false
                            lyricsTargetId = playbackState.currentTrack?.id
                            showLyricsRefresh = true
                            playbackState.currentTrack?.let { track ->
                                scope.launch { searchLyricsCandidates(playbackState, track, playbackState.lyricsRefreshSource) }
                            }
                        },
                        onLocalImport = {
                            showLyricsMenu = false
                            lyricsImportLauncher.launch("*/*")
                        },
                        onDismiss = { showLyricsMenu = false },
                    )
                    // 歌词微调：左-延后歌词，右+提前歌词，每次微调一个步长
                    if (lyricTuneVisible && playbackState.currentTrack?.lyricLines?.isNotEmpty() == true) {
                        IconButton(
                            onClick = {
                                playbackState.adjustLyricsOffset(LyricFineTuneStepMs)
                                tuneVersion++
                                tuneHintText = "+${LyricFineTuneStepMs}ms"
                                tuneHintVersion++
                            },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 16.dp)
                                .size(34.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape),
                        ) {
                            Icon(
                                imageVector = AppIcons.Remove,
                                contentDescription = stringResource(R.string.home_player_lyric_delay),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                playbackState.adjustLyricsOffset(-LyricFineTuneStepMs)
                                tuneVersion++
                                tuneHintText = "-${LyricFineTuneStepMs}ms"
                                tuneHintVersion++
                            },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 16.dp)
                                .size(34.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape),
                        ) {
                            Icon(
                                imageVector = AppIcons.Add,
                                contentDescription = stringResource(R.string.home_player_lyric_advance),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    // 浮动提示：居中显示微调的毫秒数
                    if (tuneHintText.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = tuneHintText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
                // 歌词与标题间距
                Spacer(Modifier.height(8.dp))
                // 标题与艺术家：过长时跑马灯滚动并带边缘渐隐，与横屏一致
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MarqueeInfoLine(
                        text = playbackState.currentTrack?.title.orEmpty(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 32.dp)
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val track = playbackState.currentTrack
                                    if (track != null) {
                                        menuText = track.title
                                        menuIsTitle = true
                                        showMetaMenu = true
                                    }
                                },
                            ),
                    )
                    if (playbackState.currentTrack != null) {
                        Spacer(Modifier.height(4.dp))
                        MarqueeInfoLine(
                            text = playbackState.currentTrack?.artist.orEmpty(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White,
                            modifier = Modifier
                                .padding(horizontal = 32.dp)
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        val artist = playbackState.currentTrack?.artist
                                        if (!artist.isNullOrBlank()) {
                                            menuText = artist
                                            menuIsTitle = false
                                            showMetaMenu = true
                                        }
                                    },
                                ),
                        )
                    }
                    MiniContextMenu(
                        visible = showMetaMenu,
                        onCopy = {
                            showMetaMenu = false
                            copyToClipboard(context, menuText)
                        },
                        onRename = {
                            showMetaMenu = false
                            renameIsTitle = menuIsTitle
                            renameInitValue = menuText
                            renameTargetId = playbackState.currentTrack?.id
                            showRename = true
                        },
                        onSearch = {
                            showMetaMenu = false
                            onOpenOnlineSearch(menuText)
                        },
                        onDismiss = { showMetaMenu = false },
                    )
                }
                // 艺术家与音频格式条间距
                Spacer(Modifier.height(8.dp))
                // 音频信息与进度条（与音乐面板一致，宽度收窄 15%）
                Box(
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) {
                    ProgressSection(
                        playbackState = playbackState,
                        contentColor = Color.White,
                        onFormatClick = {
                            if (currentTrackNeedsLosslessUpgrade(playbackState)) {
                                showLosslessUpgrade = true
                            }
                        },
                    )
                }
                // 进度条与控制栏间距
                Spacer(Modifier.height(8.dp))
                PlayerControls(
                    playbackState = playbackState,
                    onPlaylistClick = { onPlaylistVisibilityChange(!playlistVisible) },
                    onSpeedLongClick = onSpeedLongClick,
                    onPlaylistLongClick = { libraryAnalysis.open() },
                )
            }
        }

        PlaylistSheet(
            visible = playlistVisible,
            playbackState = playbackState,
            onDismiss = { onPlaylistVisibilityChange(false) },
        )

        LibraryAnalysisSheet(
            visible = libraryAnalysis.visible,
            playbackState = playbackState,
            analysis = libraryAnalysis,
            onDismiss = { libraryAnalysis.dismiss() },
        )

        RenameDialog(
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
            onCancel = { showRename = false },
        )

        LyricsEditDialog(
            visible = showLyricsEdit,
            initialValue = lyricsEditInitialText,
            onConfirm = { newText ->
                showLyricsEdit = false
                val track = playbackState.currentTrack
                if (track != null) {
                    scope.launch {
                        lyricsEditFailed = !applyLyricsLineEdit(context, playbackState, track, lyricsEditIndex, newText)
                    }
                }
            },
            onCancel = { showLyricsEdit = false },
        )

        // 音频信息条点击触发的无损升级确认对话框
        LosslessUpgradeDialog(
            visible = showLosslessUpgrade,
            playbackState = playbackState,
            onDismiss = { showLosslessUpgrade = false },
        )

        LocalCoverDialog(
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

        LyricsRefreshDialog(
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
            },
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
        if (lyricsImportFailed) {
            MusicErrorBanner(
                message = stringResource(R.string.music_panel_local_lyrics_failed),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = topBarInset + 10.dp, end = 16.dp),
                onDismiss = { lyricsImportFailed = false },
            )
        }
        if (lyricsEditFailed) {
            MusicErrorBanner(
                message = stringResource(R.string.music_panel_edit_lyrics_failed),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = topBarInset + 10.dp, end = 16.dp),
                onDismiss = { lyricsEditFailed = false },
            )
        }
    }
}

// 歌词长按菜单：提供在线搜索、本地歌词导入与原文编辑（有歌词行时才可编辑）
@Composable
private fun LyricsContextMenu(
    visible: Boolean,
    editEnabled: Boolean,
    onEdit: () -> Unit,
    onOnlineSearch: () -> Unit,
    onLocalImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Popup(
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
            onDismissRequest = onDismiss,
            popupPositionProvider = menuEdgePositionProvider(atTop = false),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onOnlineSearch,
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_search_title),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onLocalImport,
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_local_lyrics),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    if (editEnabled) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            onClick = onEdit,
                        ) {
                            Text(
                                text = stringResource(R.string.music_panel_edit),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// 歌词微调单次步长（毫秒）
private const val LyricFineTuneStepMs = 100L

// 封面顶部渐隐区在标题栏高度之外的延伸距离
private val TopFadeExtra = 20.dp

// 歌词区下方固定区域高度：间距、标题/艺人两行、进度条与控制栏，用于计算封面可占用高度
private val BottomFixedContentHeight = 8.dp + 8.dp + 24.dp + 4.dp + 20.dp + 8.dp + 40.dp + 8.dp + 48.dp
// 自由窗口下封面最小高度：窗口过矮时不再压缩封面，保证封面可用
private val MinCoverHeight = 120.dp
// 多窗口底部系统控制条避让留白：播放控制栏高度（48dp）的一半
private val BottomControlBarClearance = 24.dp
