package com.yichao.evilgodxu.screens.home.compact.player

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.panel.applyLocalLyrics
import com.yichao.evilgodxu.data.music.panel.applyLyricsCandidate
import com.yichao.evilgodxu.data.music.panel.applyLyricsLineEdit
import com.yichao.evilgodxu.data.music.panel.autoTranslateLyrics
import com.yichao.evilgodxu.data.music.panel.searchLyricsCandidates
import com.yichao.evilgodxu.data.music.panel.TranslateOutcome
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.permission.ensureTrackFileWritable
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest
import com.yichao.evilgodxu.navigation.LocalIsTopLayer
import com.yichao.evilgodxu.screens.home.component.dialog.LosslessUpgradeDialog
import com.yichao.evilgodxu.screens.home.component.analysis.LibraryAnalysisController
import com.yichao.evilgodxu.screens.home.component.analysis.LibraryAnalysisSheet
import com.yichao.evilgodxu.screens.home.component.audioinfo.AudioInfoSheet
import com.yichao.evilgodxu.screens.home.component.queue.PlaylistSheet
import com.yichao.evilgodxu.screens.home.component.player.PlayerControls
import com.yichao.evilgodxu.ui.component.player.currentTrackNeedsLosslessUpgrade
import com.yichao.evilgodxu.ui.component.player.MusicErrorBanner
import com.yichao.evilgodxu.ui.component.player.ProgressSection
import com.yichao.evilgodxu.ui.component.player.LyricsEditDialog
import com.yichao.evilgodxu.ui.component.player.LyricsTranslateDialog
import com.yichao.evilgodxu.ui.component.player.LyricsRefreshDialog
import com.yichao.evilgodxu.ui.component.menuEdgePositionProvider
import com.yichao.evilgodxu.ui.component.PlayModeSelectionScrim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yichao.evilgodxu.LocalPlaybackState

// 竖屏播放器：封面 + 歌词 + 信息 + 控制栏
@Composable
internal fun PortraitPlayer(
    modifier: Modifier = Modifier,
    // 授权状态，用于区分空态文案
    mediaAudioGranted: Boolean,
    // 标题栏区域高度：封面顶部渐隐区与错误横幅避让基准
    topBarInset: Dp = 0.dp,
    // 纵向切歌预览提示：滑动未松手时显示于专辑封面底部
    swipePreviewText: String? = null,
    // 快速滑动切歌出口，true 为下一曲
    onVerticalFling: ((next: Boolean) -> Unit)? = null,
    // 拖拽接管出口，宿主据此收回标题栏
    onLyricVerticalDragClaim: (() -> Unit)? = null,
    // 曲库格式分析对话框显隐：常驻首页层
    libraryAnalysis: LibraryAnalysisController,
    // 播放列表显隐，显示时禁用纵向切歌
    playlistVisible: Boolean,
    onPlaylistVisibilityChange: (Boolean) -> Unit,
    // 音频信息显隐，显示时禁用纵向切歌
    audioInfoVisible: Boolean,
    onAudioInfoVisibilityChange: (Boolean) -> Unit,
    onOpenOnlineSearch: (String) -> Unit = {},
    // 点击歌手信息：跳转到该歌手的歌单页
    onOpenArtistPlaylist: (String) -> Unit = {},
    // 「查看频谱」：跳转频谱页
    onOpenSpectrum: (Long) -> Unit = {},
    // 「编辑元数据」：跳转元数据页
    onOpenMetadata: (Long) -> Unit = {},
) {
    val playbackState = LocalPlaybackState.current

    // 弹层展开时返回键收起；被压住时让出
    BackHandler(
        enabled = LocalIsTopLayer.current &&
            (playlistVisible || audioInfoVisible || libraryAnalysis.visible)
    ) {
        when {
            libraryAnalysis.visible -> libraryAnalysis.dismiss()
            audioInfoVisible -> onAudioInfoVisibilityChange(false)
            else -> onPlaylistVisibilityChange(false)
        }
    }

    // 进度由全局 ticker 驱动

    val context = LocalContext.current
    // 文案经 Configuration 感知入口，避免旧值
    val resources = LocalResources.current
    val requestAllFilesAccess = rememberAllFilesAccessRequest()

    // 复用播放器的歌词刷新能力
    val scope = rememberCoroutineScope()
    val lyricsRefreshFailedMessage = stringResource(R.string.music_panel_lyrics_refresh_failed)
    var showLyricsRefresh by remember { mutableStateOf(false) }
    var selectedLyricsCandidate by remember { mutableStateOf<NeteaseSongSearchResult?>(null) }
    var lyricsTargetId by remember { mutableStateOf<Long?>(null) }
    // 歌词长按菜单与本地歌词导入状态
    var showLyricsMenu by remember { mutableStateOf(false) }
    var lyricsImportFailed by remember { mutableStateOf(false) }
    // 编辑对话框状态：目标为当前演唱行
    var showLyricsEdit by remember { mutableStateOf(false) }
    var lyricsEditIndex by remember { mutableIntStateOf(0) }
    var lyricsEditInitialText by remember { mutableStateOf("") }
    var lyricsEditFailed by remember { mutableStateOf(false) }
    // 补译在播放作用域继续，运行态与显隐分开
    var translating by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var translateProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var translateFailed by remember { mutableStateOf(false) }
    // 长按定格位置，避免播放推进导致错行
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
    var showLosslessUpgrade by remember { mutableStateOf(false) }
    // 模式展开态；点其它区域或选定后收起
    var playModeSelectorExpanded by remember { mutableStateOf(false) }
    // 控制栏实测高度，遮罩据此剔除该区域
    var controlsHeightPx by remember { mutableIntStateOf(0) }

    // 底部避让取固定留白与导航栏高度的较大者。
    // 系统栏通常已隐藏；多窗口下导航栏覆盖留白，避免控制栏被压住。
    val navigationBarBottom = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val bottomClearance = maxOf(BottomBarClearance, navigationBarBottom)

    // 三组由 SpaceBetween 分配余量
    // 余量只落在两处间隙，不拉伸封面，也不在底部堆空带。
    BoxWithConstraints(modifier = modifier) {
        // 封面正方形边长：全宽
        val coverSquare = maxWidth
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 封面全宽置顶，仅下缘渐隐；不强制填充，高度上限为全宽正方形
            PortraitCoverSection(
                playbackState = playbackState,
                topBarInset = topBarInset,
                swipePreviewText = swipePreviewText,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = coverSquare),
            )
            PortraitLyricsSection(
                playbackState = playbackState,
                mediaAudioGranted = mediaAudioGranted,
                onVerticalFling = onVerticalFling,
                onVerticalDragClaim = onLyricVerticalDragClaim,
                onLongPress = { positionMs ->
                    // 菜单各项都要写文件，无权限先申请再展开
                    val track = playbackState.currentTrack
                    if (track != null && ensureTrackFileWritable(requestAllFilesAccess)) {
                        lyricsMenuPositionMs = positionMs
                        showLyricsMenu = true
                    }
                },
                menuContent = {
                    LyricsContextMenu(
                visible = showLyricsMenu,
                // 编辑本行与补译需有歌词行，否则不显示
                lyricsAvailable = playbackState.currentTrack?.lyricLines?.isNotEmpty() == true,
                // 补译中置灰，避免并发写回缓存
                lyricsTaskIdle = !translating,
                onEditLine = {
                    showLyricsMenu = false
                    val track = playbackState.currentTrack
                    if (track?.lyricLines?.isNotEmpty() == true) {
                        // 按定格位置定位歌词行
                        val index = track.lyricLines
                            .indexOfLast { it.timeMs <= lyricsMenuPositionMs }
                            .coerceAtLeast(0)
                        lyricsEditIndex = index
                        // 预填时间戳 + 歌词 + 翻译行
                        lyricsEditInitialText = MusicMetadataCache.encodeLyrics(listOf(track.lyricLines[index]))
                        showLyricsEdit = true
                    }
                },
                onAutoTranslate = {
                    showLyricsMenu = false
                    val track = playbackState.currentTrack
                    if (track?.lyricLines?.isNotEmpty() == true) {
                        translating = true
                        translateProgress = null
                        showTranslateDialog = true
                        playbackState.playbackScope.launch {
                            val outcome = autoTranslateLyrics(context, playbackState, track) { done, total ->
                                withContext(Dispatchers.Main) { translateProgress = done to total }
                            }
                            withContext(Dispatchers.Main) {
                                translating = false
                                showTranslateDialog = false
                                translateProgress = null
                                // 其余结局用轻量提示，无内容属正常
                                val message = when (outcome) {
                                    is TranslateOutcome.Applied -> resources.getQuantityString(
                                        R.plurals.music_panel_auto_translate_done,
                                        outcome.translated,
                                        outcome.translated,
                                    )
                                    TranslateOutcome.NothingToDo ->
                                        resources.getString(R.string.music_panel_auto_translate_nothing)
                                    TranslateOutcome.SameLanguage ->
                                        resources.getString(R.string.music_panel_auto_translate_same_language)
                                    TranslateOutcome.Failed -> null
                                }
                                message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                translateFailed = outcome is TranslateOutcome.Failed
                            }
                        }
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
                },
            )

            // 信息 + 进度 + 控制栏贴底，底部留避让
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomClearance),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PortraitTrackInfoSection(
                    playbackState = playbackState,
                    onOpenOnlineSearch = onOpenOnlineSearch,
                    onOpenArtistPlaylist = onOpenArtistPlaylist,
                )
                // 艺术家与音频格式条间距
                Spacer(Modifier.height(8.dp))
                // 收窄 15%，与封面羽化带对齐
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
                Box(modifier = Modifier.onGloballyPositioned { controlsHeightPx = it.size.height }) {
                    PlayerControls(
                        playbackState = playbackState,
                        modeSelectorExpanded = playModeSelectorExpanded,
                        onModeSelectorExpandedChange = { playModeSelectorExpanded = it },
                        onPlaylistClick = { onPlaylistVisibilityChange(!playlistVisible) },
                        onPlaylistLongClick = { libraryAnalysis.open() },
                        onPlayPauseSwipeUp = { onAudioInfoVisibilityChange(true) },
                        onPlaylistSwipeUp = { onPlaylistVisibilityChange(true) },
                    )
                }
            }
        }

        // 模式遮罩盖住控制栏以上，其余点击即收起
        PlayModeSelectionScrim(
            visible = playModeSelectorExpanded,
            onDismiss = { playModeSelectorExpanded = false },
            modifier = Modifier.padding(
                bottom = bottomClearance + with(LocalDensity.current) { controlsHeightPx.toDp() },
            ),
        )

        PlaylistSheet(
            visible = playlistVisible,
            playbackState = playbackState,
            mediaAudioGranted = mediaAudioGranted,
            onDismiss = { onPlaylistVisibilityChange(false) },
            onViewSpectrum = onOpenSpectrum,
            onEditMetadata = onOpenMetadata,
        )

        AudioInfoSheet(
            visible = audioInfoVisible,
            playbackState = playbackState,
            onDismiss = { onAudioInfoVisibilityChange(false) },
        )

        LibraryAnalysisSheet(
            visible = libraryAnalysis.visible,
            playbackState = playbackState,
            analysis = libraryAnalysis,
            onDismiss = { libraryAnalysis.dismiss() },
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

        LyricsTranslateDialog(
            visible = showTranslateDialog,
            progress = translateProgress,
            onCollapse = { showTranslateDialog = false },
        )

        // 音频信息条点击触发的无损升级确认对话框
        LosslessUpgradeDialog(
            visible = showLosslessUpgrade,
            playbackState = playbackState,
            onDismiss = { showLosslessUpgrade = false },
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
        if (translateFailed) {
            MusicErrorBanner(
                message = stringResource(R.string.music_panel_auto_translate_failed),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, top = topBarInset + 10.dp, end = 16.dp),
                onDismiss = { translateFailed = false },
            )
        }
    }
}

// 歌词长按菜单：四项平铺；后两项需歌词行，补译中置灰
@Composable
private fun LyricsContextMenu(
    visible: Boolean,
    lyricsAvailable: Boolean,
    lyricsTaskIdle: Boolean,
    onEditLine: () -> Unit,
    onAutoTranslate: () -> Unit,
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
                    LyricsMenuItem(
                        text = stringResource(R.string.music_panel_search_title),
                        onClick = onOnlineSearch,
                    )
                    LyricsMenuItem(
                        text = stringResource(R.string.music_panel_local_lyrics),
                        onClick = onLocalImport,
                    )
                    if (lyricsAvailable) {
                        LyricsMenuItem(
                            text = stringResource(R.string.music_panel_edit_line),
                            onClick = onEditLine,
                        )
                        LyricsMenuItem(
                            text = stringResource(R.string.music_panel_auto_translate),
                            enabled = lyricsTaskIdle,
                            onClick = onAutoTranslate,
                        )
                    }
                }
            }
        }
    }
}

// 菜单项：置灰项不可点击
@Composable
private fun LyricsMenuItem(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(
            text = text,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

// 歌词微调单次步长（毫秒）
private const val LyricFineTuneStepMs = 100L

// 固定底部留白 24dp，兼作小窗控制条避让；留白恒定
private val BottomBarClearance = 24.dp
