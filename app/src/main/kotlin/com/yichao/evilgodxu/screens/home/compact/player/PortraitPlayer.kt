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

// 竖屏播放器主体：沉浸封面 + 歌词 + 标题与艺术家 + 底部控制栏
@Composable
internal fun PortraitPlayer(
    modifier: Modifier = Modifier,
    // 标题栏区域高度：封面顶部渐隐区与错误横幅避让基准
    topBarInset: Dp = 0.dp,
    // 纵向切歌预览提示：滑动未松手时显示于专辑封面底部
    swipePreviewText: String? = null,
    // 歌词区快速纵向滑动的切歌出口（true 为下一曲）：与整页上下滑动切歌同一套判定
    onVerticalFling: ((next: Boolean) -> Unit)? = null,
    // 歌词纵向拖拽接管手势的出口：宿主据此收回触摸瞬间顺带唤出的标题栏
    onLyricVerticalDragClaim: (() -> Unit)? = null,
    // 曲库格式分析对话框显隐：常驻首页层
    libraryAnalysis: LibraryAnalysisController,
    // 播放列表面板显隐：由首页层持有，显示期间禁用上下滑动切歌
    playlistVisible: Boolean,
    onPlaylistVisibilityChange: (Boolean) -> Unit,
    // 音频信息弹窗显隐：由首页层持有，显示期间禁用上下滑动切歌
    audioInfoVisible: Boolean,
    onAudioInfoVisibilityChange: (Boolean) -> Unit,
    onOpenOnlineSearch: (String) -> Unit = {},
    // 点击歌手信息：跳转到该歌手的歌单页
    onOpenArtistPlaylist: (String) -> Unit = {},
    // 播放列表高级菜单的「查看频谱」：跳转频谱分析页，只传曲目标识
    onOpenSpectrum: (Long) -> Unit = {},
    // 播放列表高级菜单的「编辑元数据」：跳转元数据编辑页，只传曲目标识
    onOpenMetadata: (Long) -> Unit = {},
) {
    val playbackState = LocalPlaybackState.current

    // 播放列表、音频信息与曲库格式分析弹层展开时，系统返回键收起弹层
    BackHandler(enabled = playlistVisible || audioInfoVisible || libraryAnalysis.visible) {
        when {
            libraryAnalysis.visible -> libraryAnalysis.dismiss()
            audioInfoVisible -> onAudioInfoVisibilityChange(false)
            else -> onPlaylistVisibilityChange(false)
        }
    }

    // 播放进度由 MusicPlaybackState 全局 ticker 驱动，此处不再独立轮询

    val context = LocalContext.current
    // 提示文案在协程中取用，需经 Configuration 感知的资源入口，避免语言/字体缩放切换后取到旧值
    val resources = LocalResources.current

    // 长按功能状态：复用播放器共享的歌词刷新能力
    val scope = rememberCoroutineScope()
    val lyricsRefreshFailedMessage = stringResource(R.string.music_panel_lyrics_refresh_failed)
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
    // 自动补译：任务在播放作用域执行，进度对话框收起后仍继续，故运行态与对话框显隐分开
    var translating by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var translateProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var translateFailed by remember { mutableStateOf(false) }
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
    var showLosslessUpgrade by remember { mutableStateOf(false) }
    // 播放模式选择展开态：展开时控制栏让位给四种播放模式，点击其他区域或选定模式后收起
    var playModeSelectorExpanded by remember { mutableStateOf(false) }
    // 控制栏实测高度：展开遮罩据此剔出底部控制栏所占区域，保证四个模式按钮仍可点击
    var controlsHeightPx by remember { mutableIntStateOf(0) }

    // 底部避让量：控制栏与屏幕底缘的间距，取固定留白与系统导航栏（三键/手势）高度中的较大者。
    // 首页全沉浸隐藏了两条系统栏，正常路径下导航栏高度为 0，此时由固定留白决定，
    // 使底部留白不随屏幕尺寸与高宽比变化；导航栏被外部原因显示出来时（独立窗口、切页过渡、
    // ROM 不认隐藏请求）、或处于多窗口（自由窗口/分屏，小窗底部另有系统控制条）时，
    // 导航栏高度覆盖固定留白，控制栏同样不被压住
    val navigationBarBottom = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val bottomClearance = maxOf(BottomBarClearance, navigationBarBottom)

    // 外层容器：沉浸封面置顶，下方依次为歌词组与歌曲信息/控制组。
    // 三组之间由 SpaceBetween 分配高度：封面保持全宽正方形（窗口过矮时按可用高度收缩），
    // 富余空间只落入封面↔歌词、歌词↔歌曲信息两处，把歌词组夹在中间均分，
    // 既不拉伸封面，也不在屏幕底部堆成一条随屏幕尺寸忽高忽低的空带
    BoxWithConstraints(modifier = modifier) {
        // 封面正方形边长：全宽
        val coverSquare = maxWidth
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 沉浸式专辑封面：全宽置顶（含状态栏后方），仅下边缘渐隐融入封面衍生背景。
            // 不强制填充：剩余高度留给外层 SpaceBetween 分给两侧留白；高度上限为全宽正方形，
            // 窗口过矮时按可用高度收缩，优先保证下方内容完整
            PortraitCoverSection(
                playbackState = playbackState,
                topBarInset = topBarInset,
                swipePreviewText = swipePreviewText,
                onOpenMetadata = onOpenMetadata,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = coverSquare),
            )
            PortraitLyricsSection(
                playbackState = playbackState,
                onVerticalFling = onVerticalFling,
                onVerticalDragClaim = onLyricVerticalDragClaim,
                onLongPress = { positionMs ->
                    // 歌词菜单里的编辑本行、在线刷新、本地导入、自动补译都要写回音频文件：
                    // 无权限时不展开菜单，直接去元数据编辑页，由那一页统一申请
                    val track = playbackState.currentTrack
                    if (track != null && ensureTrackFileWritable(track.id, onOpenMetadata)) {
                        lyricsMenuPositionMs = positionMs
                        showLyricsMenu = true
                    }
                },
                menuContent = {
                    LyricsContextMenu(
                visible = showLyricsMenu,
                // 编辑本行与自动补译都以「有歌词行」为前提，无歌词时均不展示
                lyricsAvailable = playbackState.currentTrack?.lyricLines?.isNotEmpty() == true,
                // 补译进行中（含后台执行）置灰，避免并发写回同一份歌词缓存
                lyricsTaskIdle = !translating,
                onEditLine = {
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
                                // 失败以外的结局都用轻量提示：没有可补译的内容属于正常结果
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
                        scope.launch { searchLyricsCandidates(context, playbackState, track, playbackState.lyricsRefreshSource) }
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

            // 歌曲信息、进度条与控制栏：作为最后一组贴底排列，底部留出固定避让间距
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
                    onOpenMetadata = onOpenMetadata,
                )
                // 艺术家与音频格式条间距
                Spacer(Modifier.height(8.dp))
                // 音频信息与进度条（左右各收窄 15%，与封面的羽化过渡带对齐）
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

        // 播放模式选择展开期间的点击遮罩：盖住控制栏以上的区域，控制栏本身留出，
        // 使四个模式按钮独占交互，其余位置点击即收起并复位控制按钮
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
                    scope.launch { searchLyricsCandidates(context, playbackState, track, source) }
                }
            },
            onRefresh = {
                val track = playbackState.currentTrack
                if (track != null && track.id == lyricsTargetId) {
                    selectedLyricsCandidate = null
                    scope.launch { searchLyricsCandidates(context, playbackState, track, playbackState.lyricsRefreshSource) }
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

// 歌词长按菜单：在线搜索、本地歌词导入、编辑本行与自动补译四个动作平铺一级；
// 后两项以「有歌词行」为前提，补译进行中（含后台执行）置灰禁用，避免并发写回歌词缓存
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

// 控制栏与屏幕底缘的固定留白：即使导航栏被隐藏也保留的呼吸空间，兼作多窗口下
// 小窗系统控制条的避让量。取控制栏高度（48dp）的一半，底部留白由此恒定，
// 不再由「屏幕高宽差减去内容高度」的余量决定
private val BottomBarClearance = 24.dp
