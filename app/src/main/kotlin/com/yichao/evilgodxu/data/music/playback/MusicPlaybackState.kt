package com.yichao.evilgodxu.data.music.playback

import android.content.ContentResolver
import android.content.Context
import android.media.AudioTrack
import android.media.MediaScannerConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CoverSkipRegistry
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.EmbeddedCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.music.highlight.HighlightScanner
import com.yichao.evilgodxu.data.music.highlight.HighlightStore
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import java.io.File
import kotlin.jvm.JvmName
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MusicPlaybackState(
    private val metadataEnricher: MetadataEnricher,
    private val playlistStore: PlaylistStore,
) {

    companion object {
        // 定时关闭时长（分钟）：单次调节步长、下限与上限
        const val TIMER_STEP_MINUTES = 5
        const val TIMER_MIN_MINUTES = 5
        const val TIMER_MAX_MINUTES = 999
        // 播放期间周期性持久化间隔：保证冷启动/异常退出也能恢复当前曲目与进度
        private const val STATE_PERSIST_INTERVAL_MS = 3000L
        // 常规进度轮询周期：位置只喂进度条与持久化，200ms 足够
        private const val POSITION_TICK_MS = 200L
        // 片段播放期间的进度轮询周期：首尾的音量淡入淡出要逐格推进，
        // 沿用 200ms 的台阶会让斜坡听成几次跳变
        private const val HIGHLIGHT_TICK_MS = 40L
        // 片段首尾的音量斜坡时长：淡入与淡出各占一段，靠降低音量掩掉硬切
        private const val HIGHLIGHT_FADE_MS = 800L
        // 单曲循环回卷判定：位置回退超过该值且曾越过曲目中部，视为一次完整播放
        private const val LOOP_RESTART_MIN_JUMP_MS = 3000L
        // 回卷检测与过渡回调记录的去重冷却：同一次循环只计入一次完整播放
        private const val AUTO_COUNT_COOLDOWN_MS = 2000L
        // 进度单调复位兜底：未触发切歌/拖动回调但位置大幅回退（如切换歌单重载同 ID 曲目）时视为重置；
        // 小幅回退仍按流媒体回锚处理，保持进度单调
        private const val MONO_REBASELINE_JUMP_MS = 3000L
        // 切歌预取内嵌封面前的稳定期由 ArtworkCacheController 承担

        // 播放器持久化的四份落盘落点：名字即持久化标识，读写与存储页统计共用这些声明
        internal val PLAYLIST_CACHE_STORE =
            PersistedData("music_playlist_cache_preferences", PersistedFileKind.SHARED_PREFS)
        internal val SEARCH_HISTORY_STORE =
            PersistedData("music_search_history_preferences", PersistedFileKind.SHARED_PREFS)
        internal val RECENT_PLAYED_STORE =
            PersistedData("music_recent_played_preferences", PersistedFileKind.SHARED_PREFS)
        internal val BOOT_MIRROR_STORE =
            PersistedData("music_boot_mirror_preferences", PersistedFileKind.SHARED_PREFS)
    }

    // 上次持久化播放状态的时刻，用于播放期间节流写入
    private var lastStatePersistAt = 0L

    // 冷启动恢复任务去重：并发调用方共享同一恢复任务并等待完成，
    // 避免界面、悬浮窗与授权扫描各自触发重复的读盘与解析
    private val restoreMutex = Mutex()
    private var restoreJob: Deferred<Unit>? = null
    var appContext: Context? = null
    var mediaController: MediaController? by mutableStateOf(null)
    var player: Player? by mutableStateOf(null)
    private var suppressAutoNext = false
    val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // 换曲（含单曲循环重播同一曲目）后上一曲的歌词进度不再适用
            invalidateLyricTimeline()
            // 曲目自然播完即计一次完整播放，作为常听收录依据：
            // AUTO=自动续播/单曲结束切下一首；REPEAT=单曲循环重播当前曲目。
            // 手动切歌(SEEK)、列表变更(PLAYLIST_CHANGED)非自然结束，不计入。
            // 需在更新 currentTrack 前记录，此处 currentTrack 仍为刚播完的上一首。
            // 心动模式播的是 30–45 秒的副歌片段，不是一次完整播放：计入会把每首都刷成「听过的」，
            // 污染常听歌单
            if (playMode != PlayMode.Highlight &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                currentTrack?.id?.let { recordPlayed(it) }
            }
            if (sleepTimer.stopAfterCurrentTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 定时关闭：当前曲目自然结束 → 停止播放
                sleepTimer.complete(songFinished = true)
                return
            }
            // 插队队列：仅自然切换时消费队列；队列播完后接续原播放位置
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (playNextQueue.isNotEmpty()) {
                    val queued = playNextQueue.first()
                    playNextQueue = playNextQueue.drop(1)
                    val queuedIndex = playlist.indexOfFirst { it.id == queued.id }
                    if (queuedIndex >= 0) {
                        playbackScope.launch {
                            playTrackAt(
                                appContext ?: return@launch,
                                this@MusicPlaybackState,
                                queuedIndex,
                                clearQueue = false,
                                switchKind = TrackSwitchKind.Next,
                            )
                        }
                        return
                    }
                } else if (queueResumeTrackId != null) {
                    val resumeTrackId = queueResumeTrackId
                    queueResumeTrackId = null
                    val resumeIndex = playlist.indexOfFirst { it.id == resumeTrackId }
                    val next = calculateIndex(direction = 1, repeatOne = true, from = resumeIndex)
                    if (next in playlist.indices && next != currentIndex) {
                        playbackScope.launch {
                            playTrackAt(
                                appContext ?: return@launch,
                                this@MusicPlaybackState,
                                next,
                                clearQueue = false,
                                switchKind = TrackSwitchKind.Next,
                            )
                        }
                        return
                    }
                }
            }
            // 待接入队列（切换歌单时当前曲目仍在播放）：播放器自然接续的曲目仍属旧队列，
            // 改由新队列的待接入位置接管，避免切歌单后继续播上一个歌单
            val pendingStart = pendingQueueStartIndex
            if (pendingStart != null && playlist.isNotEmpty() &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            ) {
                pendingQueueStartIndex = null
                playbackScope.launch {
                    playTrackAt(
                        appContext ?: return@launch,
                        this@MusicPlaybackState,
                        pendingStart.coerceIn(0, playlist.size - 1),
                        clearQueue = false,
                        switchKind = TrackSwitchKind.Next,
                    )
                }
                return
            }
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            val index = playlist.indexOfFirst { it.id == id }
            if (index >= 0) {
                // 续播锚点：playTrackAt 以保存位置起播时已记录目标位置，本回调（异步派发）据此保留
                // 已还原的进度，避免把进度清空到 0 再回填；真实切歌（无锚点）才复位到起点。
                // 仅在锚点归属曲目上消费：无损升级就地换源时，旧源的过渡回调可能后到，
                // 提前消费会使新文件丢失续播位置
                val resumePos = if (resumeAnchorTrackId == id) {
                    resumeAnchorPosition.also {
                        resumeAnchorPosition = -1L
                        resumeAnchorTrackId = -1L
                    }
                } else {
                    -1L
                }
                // 播放器自行接续（自动下一首）不经切歌入口，此处补记类型：漏记则会沿用上一次的
                // 类型，本次接续的横移方向随之失真。单曲循环重播同一曲目标识未变，不重记
                if (currentTrack?.id != id) beginTrackSwitch(TrackSwitchKind.Next)
                // 自动接续同样要在起播前落定源位深与声道（见 recordSourceBitDepthBeforePlayback）
                recordSourceBitDepthBeforePlayback(playlist[index])
                currentIndex = index
                currentTrack = playlist[index]
                isPrepared = false
                currentPosition = if (resumePos > 0L) resumePos else 0L
                duration = if (resumePos > 0L) playlist[index].duration else 0L
                // 切歌或单曲循环重播：复位进度单调基准，允许进度回到起点
                lastMonoMediaId = null
                // 切换曲目即持久化最新 URI，确保后台自动下一首也能被冷启动恢复
                persistState()
                // 切歌即落盘该曲目封面与背景取色，冷启动首帧可直接出图出背景，不依赖退出时机
                artworkCache.cacheCurrentCoverAndColor(playlist[index])
                // 切歌后主动预读新曲源格式，避免信息条等待解码回填而长时间空白
                appContext?.let { refreshIdleTrackFormatInfo(it) }
            }
            // 切换曲目后清理未在播放的在线歌曲，避免在线播放曲目在播放列表中常驻；
            // 元数据补全只在缓存完成时执行一次，切歌不再触发，避免重复写封面并触发系统级文件扫描
            cleanupIdleOnlineTracks()
            // 再次从控制器校正当前曲目，确保 UI 与真实音频一致（在线曲目切换时尤其关键）
            syncPlaybackState()
            // 心动模式：当前曲目定位不到副歌就跳过，否则为下一首预备片段
            settleHighlightOnTrackChange()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // 位置不连续即真实跳变：作废歌词时间轴快照，改由新的播放位置重新起步
            invalidateLyricTimeline()
            // 手动拖动进度会触发 SEEK 类位置不连续：重置回卷检测基准，
            // 避免把"拖回开头"误判为单曲循环完整播放
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                lastTickPosition = 0L
                lastMonoMediaId = null
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val controller = mediaController ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    isPrepared = true
                    ensurePositionTicker()
                    if (closeSearchResultsOnReady) {
                        closeSearchResultsOnReady = false
                        isSearchMode = false
                        showSearchResults = false
                        searchQuery = ""
                        searchResults = emptyList()
                        searchPending = emptyList()
                        searchPendingFull = false
                        pendingSearchResults = emptyList()
                    }
                    // 音质试播就绪即播放成功：关闭音质对话框并清除待确认标记
                    if (pendingQualityPlayTrackId != null) {
                        pendingQualityPlayTrackId = null
                        qualityBusy = false
                        qualityPickTrack = null
                        qualityError = null
                    }
                    syncPlaybackState()
                }
                Player.STATE_ENDED -> {
                    isPlaying = false
                    currentPosition = duration
                    // REPEAT_MODE_OFF 播完整个时间线末尾（无切歌回调）时兜底计入完整播放；
                    // 常规自然播完/单曲循环已在 onMediaItemTransition 中记录，此处不会重复。
                    // 心动模式同理不计入：片段播完不构成完整播放
                    if (playMode != PlayMode.Highlight) currentTrack?.id?.let { recordPlayed(it) }
                    if (suppressAutoNext) {
                        suppressAutoNext = false
                        return
                    }
                    if (sleepTimer.stopAfterCurrentTrack) {
                        // 定时关闭：曲目播毕停止播放
                        sleepTimer.complete(songFinished = true)
                        return
                    }
                    val next = autoNextIndex()
                    if (next >= 0) {
                        playbackScope.launch {
                            playTrackAt(
                                appContext ?: return@launch,
                                this@MusicPlaybackState,
                                next,
                                clearQueue = false,
                                switchKind = TrackSwitchKind.Next,
                            )
                        }
                    }
                }
                // 缓冲中：状态流转由 READY/ENDED 驱动，此处无需额外处理
                Player.STATE_BUFFERING -> Unit
                // 空闲态：错误/停止路径已各自复位，此处无需额外处理
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // 音质试播失败：移除刚加入的试播曲目（不残留播放列表），保留对话框供用户换其它音质
            val pendingId = pendingQualityPlayTrackId
            if (pendingId != null && qualityPickTrack != null) {
                pendingQualityPlayTrackId = null
                qualityBusy = false
                qualityError = appContext?.getString(R.string.music_panel_quality_failed)
                removeTrack(pendingId)
            }
            // errorMsg 为可空类型，appContext 为空时置 null（播放不会发生，正常显示无错误）
            errorMsg = appContext?.getString(R.string.music_panel_play_failed)
            isPlaying = false
            isPrepared = false
            stopPositionTicker()
            closeSearchResultsOnReady = false
            pendingSearchResults = emptyList()
            suppressAutoNext = true
            mediaController?.stop()
        }
    }
    var isPlaying by mutableStateOf(false)
    var isPrepared by mutableStateOf(false)
    val isPlayerActive: Boolean
        get() = mediaController?.let { ctrl ->
            ctrl.isPlaying || ctrl.playbackState == Player.STATE_BUFFERING
        } ?: false
    var duration by mutableLongStateOf(0L)
    // 播放位置：由状态层统一维护，按控制器回报单调推进（真实跳变除外，见 syncPlaybackPosition），
    // 供进度条使用。它是控制器回报的「峰值包络」——只在回报超过历史最大值时才前进，
    // 故表现为一跳一跳的台阶，不适合直接驱动歌词渲染
    var currentPosition by mutableLongStateOf(0L)

    // 即时播放位置：控制器在位时读其当前值，比按固定周期刷新的 currentPosition 更贴近此刻进度，
    // 且连续推进（控制器按时间外推），不会出现台阶；控制器未连接/已释放或回报非法值时回退到状态值。
    // 供歌词的跟随锚点取用：锚点用于判断「是否需要向上对齐」，用台阶值会让歌词跟着台阶一跳一跳
    val livePositionMs: Long
        get() = mediaController?.currentPosition?.takeIf { it >= 0L } ?: currentPosition

    /**
     * 当前项的片段起点相对整曲的偏移（毫秒），未裁剪时恒为 0。
     *
     * 裁剪生效后控制器回报的进度以**片段起点**为原点，而歌词时间轴用的是曲目内的绝对时间。
     * 二者只在这里换算一次：下游的歌词面板、悬浮窗与逐字对齐都按绝对时间工作，无需各改一处。
     * 进度条反其道而行 —— 它要的正是片段内的相对进度，故不参与本偏移。
     *
     * 判据取「播放器里那一项」而非 [playMode]：切换模式的瞬间模式标志已经变了而队列还没换，
     * 按标志判断会算错坐标系。
     */
    val highlightPositionOffsetMs: Long
        get() = mediaController?.currentMediaItem?.clippingConfiguration
            ?.startPositionMs?.takeIf { it > 0L } ?: 0L

    /**
     * 歌词时间轴所用的播放位置：曲目内的**绝对**时间。
     *
     * 心动模式下控制器回报的进度以片段起点为原点，而歌词时间轴是整曲的绝对时间。换算只在这里
     * 做一次，歌词面板与悬浮窗都取本值 —— 它们原先直接读 [livePositionMs]，于是在心动模式下
     * 整段歌词都落后一个片段起点，表现为「歌词没有跟随片段」。
     *
     * 进度条反其道而行：它要的正是片段内的相对进度（[currentPosition] / [duration]），不参与本换算。
     */
    val lyricPositionMs: Long
        get() = livePositionMs + highlightPositionOffsetMs

    /**
     * 把整曲的绝对时间换算到播放器当前项的坐标系，供以歌词行为目标的跳转使用。
     *
     * 心动模式下 seek 的目标以片段起点为原点，而歌词行给出的是整曲绝对时间；不换算就会落到
     * 片段之外被播放器钳到边界。落在片段之前的行一律起于片段起点。
     */
    fun toPlayerPosition(absoluteMs: Long): Long =
        (absoluteMs - highlightPositionOffsetMs).coerceIn(0L, duration.coerceAtLeast(0L))

    /**
     * 可落盘的播放位置。
     *
     * 心动模式下控制器回报的是片段内进度，落盘须换算回整曲的绝对位置 ——
     * 恢复时 [playTrackAt] 会按同一套换算还原到片段坐标系，两个方向对称。
     */
    private val persistablePositionMs: Long
        get() = currentPosition + highlightPositionOffsetMs

    private val _playlist = mutableStateOf<List<MusicTrack>>(emptyList())
    // 列表内容版本：后台排序回来时据此判断快照是否仍代表当前列表，过期结果直接丢弃
    private var playlistRevision = 0
    var playlist: List<MusicTrack>
        get() = _playlist.value
        set(value) {
            _playlist.value = value
            playlistRevision++
            cachedMediaItems = null
        }
    /** 缓存 playlist 对应的 MediaItem 列表，避免切歌时重复构建 */
    var cachedMediaItems by mutableStateOf<List<androidx.media3.common.MediaItem>?>(null)
    var currentIndex by mutableIntStateOf(-1)
    var currentTrack by mutableStateOf<MusicTrack?>(null)

    // 最近一次曲目变更的类型：封面、整屏底色与歌曲信息的过渡据此判定。
    // 随曲目一并被界面读取，故写入必须与曲目状态同帧，见 beginTrackSwitch
    var lastSwitchKind by mutableStateOf(TrackSwitchKind.Select)
        private set
    var playMode by mutableStateOf(PlayMode.RepeatAll)
    var errorMsg by mutableStateOf<String?>(null)
    var isScanning by mutableStateOf(false)
    // 元数据补全（封面解码 / 歌词读取）进行中：曲库分析的自动触发据此让路，
    // 避免频谱解码与封面解码在同一时间窗内争抢 CPU 与原生解码器内存
    var isEnrichingMetadata by mutableStateOf(false)
    var isLyricsVisible by mutableStateOf(false)

    // 在线搜索与在线资源匹配的界面状态收在 SearchPanelState 中；
    // 此处按原属性名转发，界面侧无需感知拆分
    private val searchPanel = SearchPanelState()

    var isSearchMode by searchPanel::isSearchMode
    var searchQuery by searchPanel::searchQuery
    var searchSource by searchPanel::searchSource
    var searchResults by searchPanel::searchResults
    val searchHistory: List<String> get() = searchHistoryStore.entries
    var isSearching by searchPanel::isSearching
    var searchJob by searchPanel::searchJob
    var searchPage by searchPanel::searchPage
    var isLoadingMore by searchPanel::isLoadingMore
    var hasMoreSearchResults by searchPanel::hasMoreSearchResults
    var searchPending by searchPanel::searchPending
    var searchPendingFull by searchPanel::searchPendingFull
    var searchLoadJob by searchPanel::searchLoadJob
    var showSearchResults by searchPanel::showSearchResults
    var pendingSearchResults by searchPanel::pendingSearchResults
    var closeSearchResultsOnReady by searchPanel::closeSearchResultsOnReady
    var qualityPickTrack by searchPanel::qualityPickTrack
    var qualityBusy by searchPanel::qualityBusy
    var qualityError by searchPanel::qualityError
    var pendingQualityPlayTrackId by searchPanel::pendingQualityPlayTrackId
    var losslessUpgradeBusy by searchPanel::losslessUpgradeBusy
    var losslessUpgradeError by searchPanel::losslessUpgradeError
    var losslessUpgradeCandidates by searchPanel::losslessUpgradeCandidates
    var isLosslessUpgradeSearching by searchPanel::isLosslessUpgradeSearching
    var losslessUpgradeSource by searchPanel::losslessUpgradeSource
    var coverCandidates by searchPanel::coverCandidates
    var isCoverSearching by searchPanel::isCoverSearching
    var localCoverCandidates by searchPanel::localCoverCandidates
    // 封面写入成功后自增：封面写进音频文件后其 URI 不变而系统略缩图已变，
    // 通知封面组件重新取系统略缩图
    var coverRevision by mutableIntStateOf(0)
    var lyricsCandidates by searchPanel::lyricsCandidates
    var isLyricsSearching by searchPanel::isLyricsSearching
    var isLyricsRefreshing by searchPanel::isLyricsRefreshing
    var lyricsRefreshError by searchPanel::lyricsRefreshError
    var lyricsRefreshSource by searchPanel::lyricsRefreshSource
    var coverRefreshSource by searchPanel::coverRefreshSource

    // ===== 心动模式 =====
    // 连续跳过计数：整队列都无可定位副歌时防止回绕空转
    private var highlightSkipStreak = 0
    // 心动模式自行退出的一次性原因，由界面消费后清空。退出有多种缘由（定位不到副歌、进入直出），
    // 界面据此说明模式为何自己变了 —— 否则用户会当成失灵
    var highlightExitNotice by mutableStateOf<HighlightExitReason?>(null)
    // 全库片段扫描任务：同一时刻只跑一个，重复触发直接返回
    private var highlightScanJob: Job? = null

    private fun hasUriAccess(context: Context, audioUri: String): Boolean {
        val uri = audioUri.toUri()
        if (context.contentResolver.persistedUriPermissions.none {
                it.uri == uri && it.isReadPermission
            }) return false
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPlaybackState", "检查媒体访问权限失败", e)
            false
        }
    }

    suspend fun removeUnavailableExternalTracks(context: Context) {
        // 文件访问探测属 I/O 操作，在 IO 线程执行避免阻塞主线程
        val unavailableIds = withContext(Dispatchers.IO) {
            playlist
                .filter { track ->
                    track.path.isBlank() &&
                        track.audioUri.isNotBlank() &&
                        runCatching {
                            val scheme = track.audioUri.toUri().scheme
                            scheme != null && scheme !in listOf("http", "https")
                        }.getOrElse { false } &&
                        runCatching { track.audioUri.toUri().scheme == ContentResolver.SCHEME_CONTENT }.getOrElse { false } &&
                        !hasUriAccess(context, track.audioUri)
                }
                .map { it.id }
                .toSet()
        }
        if (unavailableIds.isEmpty()) return

        withContext(Dispatchers.Main) {
            val currentWasRemoved = currentTrack?.id in unavailableIds
            playlist = playlist.filterNot { it.id in unavailableIds }
            currentIndex = playlist.indexOfFirst { it.id == currentTrack?.id }
            if (currentWasRemoved) {
                mediaController?.stop()
                currentTrack = null
                currentIndex = -1
                isPlaying = false
                isPrepared = false
                currentPosition = 0L
                duration = 0L
                playbackStateStore.clear(context)
            }
            persistPlaylist()
        }
    }

    // 缓存下载进行中的曲目 ID 集合：切歌清理时保留这些曲目，等待下载完成后将索引指向本地文件
    val cacheInProgressIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    // 自动清理未在播放的纯在线流曲目；已缓存为本地文件或缓存进行中的曲目保留，保证离线播放不中断
    fun cleanupIdleOnlineTracks() {
        // 以控制器实际播放项为权威来源，避免 UI 状态与真实音频脱同步
        val activeId = mediaController?.currentMediaItem?.mediaId?.toLongOrNull() ?: currentTrack?.id
        val kept = playlist.filter { track ->
            track.id == activeId || track.id in cacheInProgressIds || !isOnlineStreaming(track)
        }
        if (kept.size == playlist.size) return
        playlist = kept
        currentIndex = kept.indexOfFirst { it.id == activeId }
        persistPlaylist()
        // 列表收缩后重新从控制器校正当前曲目，保证显示与实际播放一致
        syncPlaybackState()
    }

    // 判定是否为在线流媒体曲目：无本地路径且音频地址为 http(s)
    private fun isOnlineStreaming(track: MusicTrack): Boolean {
        if (track.path.isNotBlank()) return false
        val scheme = runCatching { track.audioUri.toUri().scheme }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    // advanceToNext：删除的是当前曲目时，自动递补原列表顺序中的下一首，避免播放器内容空白
    fun removeTrack(trackId: Long, advanceToNext: Boolean = false) {
        if (playlist.none { it.id == trackId }) return
        val removedCurrent = currentTrack?.id == trackId
        // 记录删除前是否正在播放，决定递补后是继续播放还是仅切换显示
        val wasPlaying = mediaController?.isPlaying == true || isPlaying
        // 原列表顺序中删除曲目之后的下一首（环形取），列表仅剩自身时无递补
        val nextTrack = if (removedCurrent) {
            val oldIndex = playlist.indexOfFirst { it.id == trackId }
            playlist.getOrNull((oldIndex + 1) % playlist.size)?.takeIf { it.id != trackId }
        } else null
        playlist = playlist.filterNot { it.id == trackId }
        playNextQueue = playNextQueue.filterNot { it.id == trackId }
        if (queueResumeTrackId == trackId) queueResumeTrackId = null
        if (removedCurrent) {
            val nextIndex = nextTrack?.let { playlist.indexOfFirst { t -> t.id == it.id } } ?: -1
            if (advanceToNext && nextIndex >= 0) {
                // 先停止旧播放，避免继续播已被删除的音频源
                mediaController?.stop()
                // 曲目由本处先行写入，类型必须与它同帧记下：随后的 playTrackAt 因目标已是当前曲目
                // 而不再补记，本处漏记就会沿用上一次的类型
                beginTrackSwitch(TrackSwitchKind.Next)
                currentIndex = nextIndex
                currentTrack = playlist[nextIndex]
                isPlaying = false
                isPrepared = false
                currentPosition = 0L
                duration = 0L
                errorMsg = null
                appContext?.let { context ->
                    playbackScope.launch {
                        playTrackAt(
                            context,
                            this@MusicPlaybackState,
                            nextIndex,
                            autoPlay = wasPlaying,
                            clearQueue = false,
                            switchKind = TrackSwitchKind.Next,
                        )
                    }
                }
            } else {
                mediaController?.stop()
                currentTrack = null
                currentIndex = -1
                isPlaying = false
                isPrepared = false
                currentPosition = 0L
                duration = 0L
            }
        } else {
            currentIndex = playlist.indexOfFirst { it.id == currentTrack?.id }
        }
        persistPlaylist()
    }

    /**
     * 歌词缓存的引用集：全量库 ∪ 当前播放列表。全量库覆盖本地曲目，当前列表兜底在线曲目
     * （在线曲目只存在于当前列表，不在全量库备份）。
     *
     * 返回 null 表示此刻判不出全量引用 —— 停留在自定义歌单且全量库备份缺失时，当前列表只是歌单子集，
     * 此时任何缓存都「看似无引用」，调用方必须整体跳过回收，否则会删掉其他曲目仍在用的共享缓存。
     *
     * @param excludeTrackId 从引用集剔出的曲目：删曲时传入待删曲目，其歌词缓存随之成为可回收的孤儿
     */
    fun lyricCacheReferences(excludeTrackId: Long? = null): Set<String>? {
        if (playlistSource != null && defaultPlaylistBackup == null) return null
        return (defaultPlaylistBackup.orEmpty() + playlist)
            .filterNot { it.id == excludeTrackId }
            .distinctBy { it.id }
            .map { it.lyricCachePath }
            .toSet()
    }

    // 彻底删除歌曲：移除音频源文件与仅该曲引用的歌词缓存，并同步库、播放队列与歌单引用
    suspend fun deleteSongPermanently(context: Context, track: MusicTrack) {
        // 删除前先按剩余曲目算好引用集；引用集不全时拿到 null，本次跳过清理而非按「无引用」误删
        val references = lyricCacheReferences(excludeTrackId = track.id)
        val deleted = withContext(Dispatchers.IO) {
            val ok = deleteAudioSource(context, track)
            if (ok) references?.let { MusicMetadataCache.cleanupOrphanedMetadata(context, it) }
            ok
        }
        // 源文件没删掉时不能把收藏/歌单当成功删除处理：下次扫描会把歌重新带回来，
        // 用户整理过的关联却已经先丢了
        if (!deleted) {
            CrashLogManager.logException(
                "MusicPlaybackState",
                "彻底删除音频源失败，保留用户关联: 歌曲=${track.title}, uri=${track.audioUri}, 路径=${track.path}",
            )
            return
        }
        defaultPlaylistBackup = defaultPlaylistBackup?.filterNot { it.id == track.id }
        removeTrack(track.id, advanceToNext = true)
        likedIds = likedIds - track.id
        removeFromRecentPlayed(track.id)
        // 读盘切到 IO：首次 getSharedPreferences 需同步解析整份歌单 JSON，不应占用主线程
        playlistStore.awaitLoaded(context)
        playlistStore.removeTrackFromAll(context, track.id)
    }

    // 删除音频源文件：返回 false 表示两种删除方式都没能删掉文件
    private fun deleteAudioSource(context: Context, track: MusicTrack): Boolean =
        deleteAudioSourceByRef(context, track.audioUri, track.path)

    // 按 URI 与本地路径删除音频源文件：先经 MediaStore 删除（同时清理媒体条目），
    // 失败则直接删本地路径并通知媒体库同步
    private fun deleteAudioSourceByRef(context: Context, audioUri: String, path: String?): Boolean {
        val uri = audioUri.takeIf { it.isNotBlank() }?.toUri()
        // 纯在线流曲目无本地文件，无需文件级删除
        if (uri?.scheme == "http" || uri?.scheme == "https") return true
        val deletedViaResolver = uri != null &&
            runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0
        if (deletedViaResolver) return true
        if (path.isNullOrBlank()) return false
        val deletedFile = runCatching { File(path).delete() }.getOrDefault(false)
        if (deletedFile) {
            // 直删文件后触发媒体扫描，使 MediaStore 中该文件的条目失效，避免歌曲重新出现
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
        return deletedFile
    }

    // 音频链路可观测状态与格式探测收在 AudioSignalPathState 中；
    // 此处按原属性名转发，播放服务与界面的写入/读取点不变
    private val audioSignal = AudioSignalPathState(
        scope = { playbackScope },
        context = { appContext },
        currentTrack = { currentTrack },
    )

    var audioSignalPathFormat by audioSignal::audioSignalPathFormat
    var audioSignalPathTrackId by audioSignal::audioSignalPathTrackId
    var audioSignalPathSourceUri by audioSignal::audioSignalPathSourceUri

    var audioDecoderName by audioSignal::audioDecoderName

    var audioTrackMimeType by audioSignal::audioTrackMimeType
    var audioTrackDecodable by audioSignal::audioTrackDecodable
    var audioSinkFloatOutput by audioSignal::audioSinkFloatOutput
    var audioSinkOutputEncoding by audioSignal::audioSinkOutputEncoding

    var audioSinkOutputSampleRate by audioSignal::audioSinkOutputSampleRate
    var audioSinkDecodedFormat by audioSignal::audioSinkDecodedFormat

    // @Volatile 修饰的是持有方的实际字段，此处只转发读写
    var audioTrack: AudioTrack?
        get() = audioSignal.audioTrack
        set(value) { audioSignal.audioTrack = value }
    var audioSinkWrittenFrames by audioSignal::audioSinkWrittenFrames
    var usbExclusiveLatency by audioSignal::usbExclusiveLatency
    // 直出的当前成色由 AudioSignalPathState 持有
    var directOutputMode by audioSignal::directOutputMode

    var usbExclusiveRoute by audioSignal::usbExclusiveRoute

    var usbExclusiveDevice by audioSignal::usbExclusiveDevice

    var usbExclusiveClaimed by audioSignal::usbExclusiveClaimed

    var exclusiveVolumeStep by audioSignal::exclusiveVolumeStep
    var setExclusiveVolumeStep by audioSignal::setExclusiveVolumeStep

    var usbDirectOutputEnabled by audioSignal::usbDirectOutputEnabled
    var usbExclusiveEnabled by audioSignal::usbExclusiveEnabled

    var likedIds by mutableStateOf<Set<Long>>(emptySet())

    private val recentPlayedStore = RecentPlayedStore(
        scope = { playbackScope },
        context = { appContext },
    )
    private val searchHistoryStore = SearchHistoryStore(
        scope = { playbackScope },
        context = { appContext },
    )
    private val playlistPersistence = PlaylistPersistence(
        scope = { playbackScope },
        context = { appContext },
    )
    private val startupStateLoader = StartupStateLoader(
        searchHistoryStore = searchHistoryStore,
        recentPlayedStore = recentPlayedStore,
        playlistPersistence = playlistPersistence,
    )
    private val backgroundColors = BackgroundColorMemory(
        scope = { playbackScope },
        context = { appContext },
        currentTrackUri = { currentTrack?.audioUri },
        onCurrentTrackColorsChanged = { persistBootMirror() },
    )
    private val artworkCache = ArtworkCacheController(
        scope = { playbackScope },
        context = { appContext },
        currentTrack = { currentTrack },
        playlist = { playlist },
        libraryTracks = { libraryTracks },
        onColorsReady = { uri, colors -> backgroundColors.saveFor(uri, colors) },
    )

    // 单曲循环回卷检测状态：追踪上次 tick 的播放位置，位置大幅回退即一次完整播放
    private var lastTickTrackId: Long? = null
    private var lastTickPosition = 0L
    // 最近一次完整播放记录时间与曲目，供回卷检测与过渡回调去重
    private var lastAutoCountAtMs = 0L
    private var lastAutoCountTrackId: Long? = null
    // 进度展示单调性：连续播放期间进度与时长只增不减，规避流媒体位置回锚/时长修正导致进度条倒退；
    // 手动拖动(SEEK)、切歌、单曲循环回卷时复位基准，允许进度回落
    private var lastMonoMediaId: Long? = null
    private var lastMonoPosition = 0L

    val recentPlayedIds: List<Long> get() = recentPlayedStore.ids

    // 当前播放列表来源歌单（null = 默认全量播放列表）
    var playlistSource by mutableStateOf<PlaylistSource?>(null)
    // 播放列表面板的浏览态：为 true 时面板展示播放队列，否则展示 viewedSource 指定的歌单。
    // 浏览态只决定面板展示内容，与播放队列解耦，切换时不触碰播放器
    var viewedFollowsQueue by mutableStateOf(true)
        private set
    // 面板浏览的歌单来源（null = 默认全量播放列表）
    var viewedSource by mutableStateOf<PlaylistSource?>(null)
        private set
    // 播放列表排序规则（仅对默认全量播放列表生效），随列表一并持久化
    var playlistSortField by mutableStateOf(PlaylistSortField.DEFAULT)
    var playlistSortDescending by mutableStateOf(false)
    // 默认全量播放列表备份：首次切到歌单时快照，供快捷切回默认
    var defaultPlaylistBackup by mutableStateOf<List<MusicTrack>?>(null)
    // 全量库：优先备份，否则为当前播放列表
    val libraryTracks: List<MusicTrack>
        get() = defaultPlaylistBackup ?: playlist

    // 面板切换为浏览指定歌单：只改变列表展示内容，播放队列与播放状态保持不变
    fun viewPlaylist(source: PlaylistSource?) {
        viewedFollowsQueue = false
        viewedSource = source
        persistPlaylist()
    }

    fun followPlaybackQueue() {
        if (viewedFollowsQueue) return
        viewedFollowsQueue = true
        viewedSource = null
        persistPlaylist()
    }

    // 记录一次完整播放：追加带时间戳的播放记录，并清理超出窗口的旧记录
    fun recordPlayed(trackId: Long) {
        recentPlayedStore.record(trackId)
    }

    // 从常听手动移除：清除该曲目的播放记录，期间不再自动收录
    fun removeFromRecentPlayed(trackId: Long) {
        recentPlayedStore.remove(trackId)
    }

    // 下一首播放插队队列：自然播完后依次播放队列曲目，再接续原播放位置
    var playNextQueue by mutableStateOf<List<MusicTrack>>(emptyList())
    // 建立队列时记录的当前曲目 ID，队列播完后据此接续原播放位置
    private var queueResumeTrackId: Long? by mutableStateOf(null)
    // 待接入队列的起播位置（null = 无待接入队列）：切换歌单时正在播放的曲目仍出声，
    // 新队列暂不装载以免打断播放，待该曲目播完（或用户手动切歌）后从这个位置接入播放器
    internal var pendingQueueStartIndex: Int? = null

    // 曲目是否已在下一首播放队列中
    fun isInPlayNext(trackId: Long): Boolean = playNextQueue.any { it.id == trackId }

    // 切换下一首播放：已在队列则取消插队，否则加入
    fun togglePlayNext(track: MusicTrack) {
        if (isInPlayNext(track.id)) {
            playNextQueue = playNextQueue.filterNot { it.id == track.id }
            // 队列清空后无需再接续原播放位置
            if (playNextQueue.isEmpty()) queueResumeTrackId = null
        } else {
            if (playNextQueue.isEmpty() && queueResumeTrackId == null) {
                queueResumeTrackId = currentTrack?.id
            }
            playNextQueue = playNextQueue + track
        }
    }

    // 手动切歌时清空插队队列
    fun clearPlayNextQueue() {
        playNextQueue = emptyList()
        queueResumeTrackId = null
    }

    /**
     * 记下本次曲目变更的类型。
     *
     * 所有会让当前曲目换人的路径都要经过这里——漏掉一处，该次变更就会沿用上一次的类型，
     * 横移方向随之失真。类型须与曲目状态同帧写入，界面才能读到与之配套的方向。
     */
    internal fun beginTrackSwitch(kind: TrackSwitchKind) {
        lastSwitchKind = kind
    }

    // 定时关闭的倒计时与布防由 SleepTimerController 持有；此处按原属性名转发
    private val sleepTimer = SleepTimerController(
        isPlaying = { isPlaying },
        playMode = { playMode },
        mediaController = { mediaController },
        onCountdownFinished = ::onSleepTimerCountdownFinished,
    )
    var timerMinutes by sleepTimer::timerMinutes
    var timerRemaining by sleepTimer::timerRemaining
    var onSleepTimerFinished by sleepTimer::onSleepTimerFinished

    // 播放控制协程作用域（用于曲目结束自动下一首）
    private val playbackJob = SupervisorJob()
    val playbackScope = CoroutineScope(playbackJob + Dispatchers.Main)

    // 全局进度刷新协程：播放期间由播放器状态驱动，避免多个 UI 各自轮询重复写状态
    private var positionTickerJob: Job? = null

    // 启动全局进度刷新，重复调用不重复创建
    fun ensurePositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = playbackScope.launch {
            while (isActive) {
                if (isPlaying) {
                    updatePosition()
                    syncHighlightFadeVolume()
                }
                // 片段播放期间步进要细：首尾的音量斜坡靠它逐格推进
                delay(if (isHighlightClipActive) HIGHLIGHT_TICK_MS else POSITION_TICK_MS)
            }
        }
    }

    fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    // 当前播放项是否为心动模式的裁剪片段：只有它有「首尾」，因而才需要音量淡入淡出
    private val isHighlightClipActive: Boolean
        get() = playMode == PlayMode.Highlight &&
            currentTrack?.let { HighlightStore.segmentOf(it.id) } != null

    /**
     * 把片段首尾的音量斜坡写进播放器音量。
     *
     * 片段是靠裁剪切开的，首尾都是硬切；这里在片段开头把音量由 0 升到 1、在结尾前由 1 降到 0，
     * 用降低音量做出淡入淡出，进出不再是突兀的一下。
     *
     * 位置与时长都取控制器对**当前项**的回报：裁剪生效后它们本就以片段为原点，正是斜坡要的坐标系。
     * 仅对确有片段的曲目生效 —— 心动模式下没有片段的曲目照常整曲播放，不该被从头淡入到尾。
     */
    private fun syncHighlightFadeVolume() {
        val controller = mediaController ?: return
        val target = if (isHighlightClipActive) {
            val duration = controller.duration
            val position = controller.currentPosition
            if (duration <= 0L || position < 0L) {
                1f
            } else {
                val fadeIn = position.toFloat() / HIGHLIGHT_FADE_MS
                val fadeOut = (duration - position).toFloat() / HIGHLIGHT_FADE_MS
                minOf(fadeIn, fadeOut).coerceIn(0f, 1f)
            }
        } else {
            1f
        }
        if (controller.volume != target) controller.volume = target
    }

    // ===== 歌词进度时间轴 =====
    // 歌词面板在旋转、悬浮窗建立、页面回切时会整体重建。面板本地按真实流逝时间推进的相位
    // 若不能在重建时接上，就会退回重建那一刻的播放位置，表现为「重建一次、歌词落后数百毫秒」；
    // 故时间轴快照由状态层持有：面板每轮跟随回写，重建时接着走。
    // 用普通字段而非 Compose 状态：每秒数十次写入，不该牵动其它面板重组
    private var lyricTimelineTrackId: Long? = null
    private var lyricTimelinePositionMs = 0L

    /**
     * 界面重建时的歌词起步位置。
     *
     * 取「上一块面板推进到的进度」与「即时播放位置」的较大者：前者保住面板本地已按真实流逝时间
     * 推进的相位（即时位置随位置回锚回退后可能已低于它），后者保证离开页面较久、快照明显落后时
     * 仍从当前进度起步。即时位置连续推进，只有控制器不可用时才回退到台阶式的单调位置
     */
    fun seedLyricPosition(trackId: Long?): Long {
        val snapshot = lyricTimelinePositionMs
            .takeIf { trackId != null && trackId == lyricTimelineTrackId } ?: 0L
        // 取绝对时间：心动模式下控制器回报的是片段内进度，换算回整曲时间后歌词才对得上
        return maxOf(lyricPositionMs, snapshot)
    }

    // 面板每轮跟随回写当前进度，供界面重建时接着推进
    fun recordLyricPosition(trackId: Long?, positionMs: Long) {
        if (trackId == null || trackId != lyricTimelineTrackId) {
            lyricTimelineTrackId = trackId
            lyricTimelinePositionMs = positionMs
            return
        }
        // 同一曲目内只前进：多个面板可能同时在场，落后的一方回写不应把已推进的进度拉回
        if (positionMs > lyricTimelinePositionMs) lyricTimelinePositionMs = positionMs
    }

    // 真实跳变（拖动进度、歌词拖拽跳转、切曲、循环回卷）后作废快照：
    // 下一块面板改以跳变后的即时播放位置重新起步，避免沿用跳变前的旧进度
    private fun invalidateLyricTimeline() {
        lyricTimelineTrackId = null
        lyricTimelinePositionMs = 0L
    }

    // 防止手动切歌与自动切歌并发导致状态错乱
    val playTrackMutex = Mutex()

    val hasTrack: Boolean get() = currentTrack != null

    suspend fun restoreSavedState(context: Context) {
        // 首个调用方负责恢复，其余调用方共享同一任务并等待完成（冷启动由 App 预触发）
        val job = restoreMutex.withLock {
            restoreJob ?: playbackScope.async { doRestoreSavedState(context.applicationContext) }
                .also { restoreJob = it }
        }
        job.await()
    }

    private suspend fun doRestoreSavedState(context: Context) {
        appContext = context
        // 读取侧（并发读盘与排序）由 StartupStateLoader 承担，这里只负责把结果应用到播放状态
        val restored = startupStateLoader.load(context)
        searchHistoryStore.replaceAll(restored.searchHistory)
        recentPlayedStore.replaceAll(restored.recentPlayEvents)
        val preferences = restored.preferences
        val cachedPlaylist = restored.cachedPlaylist
        val orderedCachedPlaylist = restored.orderedPlaylist
        val cachedBackup = restored.cachedBackup
        val savedSource = restored.savedSource
        val savedSortField = restored.savedSortField
        val savedSortDescending = restored.savedSortDescending
        val savedFollowsQueue = restored.savedFollowsQueue
        val savedViewedSource = restored.savedViewedSource
        val savedState = playbackStateStore.read(preferences)
        val savedUri = savedState.audioUri
        val savedPosition = savedState.position
        val savedMode = savedState.modeOrdinal
        withContext(Dispatchers.Main) {
            // 无保存来源时处于全量播放列表
            playlistSource = savedSource
            viewedFollowsQueue = savedFollowsQueue
            viewedSource = if (savedFollowsQueue) null else savedViewedSource
            playlistSortField = savedSortField
            playlistSortDescending = savedSortDescending
            defaultPlaylistBackup = cachedBackup.takeIf { it.isNotEmpty() }
            // 收藏合并自当前歌单与默认库备份，避免切歌单后库内收藏丢失
            likedIds = (cachedPlaylist + cachedBackup)
                .filter { it.isFavorite }
                .map { it.id }
                .toSet()
            if (playlist.isEmpty() && cachedPlaylist.isNotEmpty()) {
                playlist = orderedCachedPlaylist.map { it.copy(isFavorite = likedIds.contains(it.id)) }
            }
            // 列表就绪即让首帧预置的当前曲目归位并补齐下标，此后界面不再需要二次定位
            adoptSeededCurrentTrack()
            // 播放列表缓存缺失时（如首次安装、缓存被清）首帧预置的曲目无从校验，可能已不在库中：
            // 撤下交由随后的扫描恢复路径按保存的 URI 重新定位，避免显示一首无法播放的曲目
            if (playlist.isEmpty()) currentTrack = null
            pendingSavedUri = savedUri
            pendingResumePosition = savedPosition
            // 启动镜像已为同一曲目预置取色时不覆盖，比较与采纳由 BackgroundColorMemory 承担
            backgroundColors.restoreFrom(preferences)
            if (currentTrack == null) {
                currentPosition = savedPosition
            }
            playMode = PlayMode.entries.getOrElse(savedMode) { PlayMode.RepeatAll }
        }
        // 冷启动预读上次曲目的落盘封面：驻留内存后首帧可同步取用，不阻塞本次恢复
        savedUri?.let { uri -> playbackScope.launch { CurrentCoverCache.load(context, uri) } }
    }

    // 源格式预读与归属判定由 AudioSignalPathState 承担，详见其实现说明
    fun recordSourceBitDepthBeforePlayback(track: MusicTrack) =
        audioSignal.recordSourceBitDepthBeforePlayback(track)

    // 冷启动未播放时预读当前曲目格式信息，供音频信息条展示；开始播放后由解码头覆盖
    fun refreshIdleTrackFormatInfo(context: Context) =
        audioSignal.refreshIdleTrackFormatInfo(context)

    // 缓存完成后以本地缓存文件为源强制补齐当前曲目格式信息，避免在线播放期间信息条空白
    fun refreshTrackFormatInfoFromLocal(context: Context) =
        audioSignal.refreshTrackFormatInfoFromLocal(context)

    // 回到前台时校正音频信息：与当前曲目错配时清掉旧值并重新读取当前曲目源格式，
    // 保证信息条始终对应当前曲目而不依赖解码回调回填
    fun reconcileTrackFormatInfo(context: Context) =
        audioSignal.reconcileTrackFormatInfo(context)

    // 格式信息是否对应当前曲目的当前音频源（供信息条判定是否展示，避免换源后短暂错配残留）
    val isAudioSignalPathCurrent: Boolean
        get() = audioSignal.isCurrentForCurrentTrack

    fun persistPlaylist() {
        // 列表、来源、浏览态、排序与默认库备份同属一份偏好文件，由 PlaylistPersistence 合并为一次落盘
        playlistPersistence.persist(
            PlaylistPersistence.Snapshot(
                tracks = playlist,
                source = playlistSource,
                backup = defaultPlaylistBackup,
                followsQueue = viewedFollowsQueue,
                viewedSource = viewedSource,
                sortField = playlistSortField,
                sortDescending = playlistSortDescending,
            )
        )
    }

    fun addSearchHistory(query: String) {
        searchHistoryStore.add(query)
    }

    fun removeSearchHistory(query: String) {
        searchHistoryStore.remove(query)
    }

    fun clearSearchHistory() {
        searchHistoryStore.clear()
    }

    var pendingSavedUri: String? = null
    var pendingResumePosition: Long = 0L
    // 首页背景取色结果由 BackgroundColorMemory 持有与落盘；此处只转发查询入口
    // 仅当曲目与取色结果同源时返回，避免运行时切歌后旧曲目的恢复色闪帧
    fun restoredBackgroundColorFor(track: MusicTrack?): Color? =
        backgroundColors.restoredColorFor(track)?.representative

    // 无损升级替换音频文件时 URI 变化但封面/背景不变：把已持久化的取色结果改指到新 URI
    fun remapBackgroundColorUri(fromUri: String, toUri: String) {
        backgroundColors.remapUri(fromUri, toUri)
    }

    // 首页背景真实取色成功后持久化，供下次播放直接复用
    internal fun saveBackgroundColors(colors: CoverBackgroundColors) {
        backgroundColors.saveForCurrentTrack(colors)
    }



    // 扫描完成后的封面预热由 ArtworkCacheController 承担，详见其实现说明
    fun warmUpArtwork(context: Context) {
        artworkCache.warmUpArtwork(context)
    }

    // 续播锚点：playTrackAt 以保存位置起播时记录该目标，供异步派发的 onMediaItemTransition 保留已还原进度。
    // -1 表示无续播锚点（真实切歌/重播），过渡回调按常规复位进度到起点。
    internal var resumeAnchorPosition: Long = -1L

    // 续播锚点归属的曲目 ID：过渡回调只在该曲目的过渡上消费锚点。
    // 无损升级会就地替换同一曲目的音频源，此时旧源的过渡回调可能后于 playTrackAt 到达，
    // 若无归属校验会提前消费锚点，导致新文件从 0 起播（表现为升级后不续播）。
    internal var resumeAnchorTrackId: Long = -1L

    fun persistState() {
        val track = currentTrack ?: return
        // 位置取整曲的绝对位置：心动模式下控制器回报的是片段内进度，直接落盘会让恢复时对不上
        playbackStateStore.persist(track.audioUri, persistablePositionMs, playMode.ordinal)
        persistBootMirror()
    }

    // 播放状态快照的落盘与读回由 PlaybackStateStore 承担
    private val playbackStateStore = PlaybackStateStore(
        scope = { playbackScope },
        context = { appContext },
    )
    // ===== 播放启动镜像 =====
    //
    // 单键轻量副本只服务首帧；完整语义与写入点见 BootMirrorStore。
    private val bootMirrorStore = BootMirrorStore(
        scope = { playbackScope },
        context = { appContext },
    )

    /**
     * 绑定应用上下文。
     *
     * 与镜像、缓存无关，因此独立于两条启动路径：首启动没有任何可预置的内容，
     * 但后续的落盘路径仍要取用上下文。
     */
    fun attachContext(context: Context) {
        appContext = context.applicationContext
    }

    /** 是否存在可预置的启动镜像：首启动没有，据此跳过首帧前的同步读盘 */
    fun hasBootMirror(context: Context): Boolean = bootMirrorStore.has(context)

    /** 是否有已落盘的曲库缓存：首启动没有，恢复与差分都无从谈起 */
    fun hasLibraryCache(context: Context): Boolean = playlistPersistence.hasLibraryCache(context)

    /** 落盘本轮扫描所用的曲库签名，供下次冷启动比对 */
    fun writeLibrarySignature(context: Context, signature: String) {
        playlistPersistence.writeLibrarySignature(context, signature)
    }

    /** 上次扫描时的曲库签名：为空表示从未扫完过，需按首启动处理 */
    fun readLibrarySignature(context: Context): String? =
        playlistPersistence.readLibrarySignature(context)

    /**
     * 同步读取启动镜像并预置首帧状态：曲目、进度、背景取色、封面位图与歌词内容一并就位。
     *
     * 只可在冷启动的 Application.onCreate 主线程调用一次。读盘量固定为一个单键偏好、一张封面缩略图
     * 与一份歌词缓存文件，换取首帧即是完整界面、启动不出现空态与占位符。
     */
    fun seedFromBootMirror(context: Context) {
        attachContext(context)
        val snapshot = bootMirrorStore.read(context) ?: return
        val track = snapshot.track
        // 歌词内容随首帧一并读出：歌词区不再经历「空白 → 内容」。
        // 缓存文件保存的始终是原始时间戳，需与补全路径一致地应用手动偏移，否则首帧歌词会错位
        val lines = track.lyricCachePath
            .takeIf { MusicMetadataCache.isValid(context, it) }
            ?.let { MusicMetadataCache.loadLyrics(context, it) }
            ?.let { if (track.lyricOffsetMs != 0L) MusicMetadataCache.shiftLyrics(it, track.lyricOffsetMs) else it }
            .orEmpty()
        // 封面位图同步驻留：封面不再经历「占位符 → 图片」
        CurrentCoverCache.loadBlocking(context, track.audioUri)
        // 首帧时播放列表尚未恢复，下标先置 -1，由 adoptSeededCurrentTrack 在列表就绪后补齐
        currentIndex = -1
        currentTrack = track.copy(lyricLines = lines)
        currentPosition = snapshot.position
        duration = track.duration
        playMode = PlayMode.entries.getOrElse(snapshot.modeOrdinal) { PlayMode.RepeatAll }
        snapshot.colorUri?.let { backgroundColors.adopt(it, snapshot.colors) }
        pendingSavedUri = track.audioUri
        pendingResumePosition = currentPosition
    }

    // 启动镜像预置的当前曲目在播放列表就绪后归位：以列表实例为准并补齐下标（首帧时列表为空，无从定位），
    // 同时保留首帧已同步读出的歌词内容，避免镜像副本与列表实例长期并存
    private fun adoptSeededCurrentTrack() {
        if (playlist.isEmpty()) return
        val seeded = currentTrack
        val index = when {
            seeded != null -> playlist.indexOfFirst { it.id == seeded.id }
            else -> pendingSavedUri?.let { uri -> playlist.indexOfFirst { it.audioUri == uri } } ?: -1
        }.takeIf { it >= 0 } ?: 0
        currentIndex = index
        val target = playlist[index]
        currentTrack = if (seeded != null && seeded.id == target.id && seeded.lyricLines.isNotEmpty()) {
            target.copy(lyricLines = seeded.lyricLines)
        } else {
            target
        }
    }

    // 同步落盘启动镜像：写入当前曲目、进度、播放模式与背景取色，供下次冷启动首帧同步取用
    private fun persistBootMirror() {
        val track = currentTrack ?: return
        bootMirrorStore.write(
            track = track,
            position = persistablePositionMs,
            modeOrdinal = playMode.ordinal,
            colorUri = backgroundColors.uri.orEmpty(),
            colors = backgroundColors.colors,
        )
    }

    fun softRelease() {
        stopPositionTicker()
        persistState()
        currentTrack?.let { track ->
            pendingSavedUri = track.audioUri
            pendingResumePosition = persistablePositionMs
        }
        // 清掉未消费的续播锚点，避免释放后残留锚点被后续非续播的过渡回调误用
        resumeAnchorPosition = -1L
        resumeAnchorTrackId = -1L
        mediaController?.let { controller ->
            controller.pause()
            controller.stop()
            controller.removeListener(controllerListener)
            playbackScope.launch { controller.release() }
        }
        mediaController = null
        player = null
        isPlaying = false
        // 释放播放器后复位单调基准，避免恢复播放时进度被残留基准钳到高位
        lastMonoMediaId = null
    }

    fun release() {
        stopPositionTicker()
        persistState()
        currentTrack?.let { track ->
            pendingSavedUri = track.audioUri
            pendingResumePosition = persistablePositionMs
        }
        // 清掉未消费的续播锚点，避免释放后残留锚点被后续非续播的过渡回调误用
        resumeAnchorPosition = -1L
        resumeAnchorTrackId = -1L
        mediaController?.let { controller ->
            playbackScope.launch {
                controller.stop()
                controller.removeListener(controllerListener)
                controller.release()
            }
        }

        mediaController = null
        player = null
        currentPosition = 0L
        // 进度已归零：歌词时间轴一并作废，避免下次重建时按释放前的旧进度起步
        invalidateLyricTimeline()
        isPlaying = false
        isPrepared = false
        duration = 0L
        // 释放播放器后复位单调基准，避免恢复播放时进度被残留基准钳到高位
        lastMonoMediaId = null
        errorMsg = null
        stopTimer()
    }

    // 定时关闭到点收尾：停止播放并请求结束应用。
    // songFinished 表示当前曲目已完整播完，此时「当前曲目」要落到它的下一首——
    // 播完的这一首已不属于待播内容，留作当前曲目会让下次启动点击播放时重播它
    private fun onSleepTimerCountdownFinished(songFinished: Boolean) {
        if (songFinished) adoptNextTrackAfterFinish()
        release()
        playbackScope.launch {
            // 退出会终止进程：先把续播目标落盘，再请求退出
            playbackStateStore.inFlightWrite?.join()
            bootMirrorStore.inFlightWrite?.join()
            onSleepTimerFinished?.invoke()
        }
    }

    // 当前曲目播毕：当前曲目落到下一首（进度归零），下次启动即从这首接着播。
    // 与自然切歌同口径——此刻播放器已走到下一首，状态随之对齐；列表仅此一首时无下一首可落
    private fun adoptNextTrackAfterFinish() {
        val next = nextIndex()
        val track = playlist.getOrNull(next) ?: return
        if (track.id == currentTrack?.id) return
        currentIndex = next
        currentTrack = track
        currentPosition = 0L
        duration = track.duration
        isPlaying = false
        isPrepared = false
    }

    // 启动定时关闭（分钟）：到点后播完当前整曲即停止播放并退出应用
    fun startTimer(minutes: Int) {
        sleepTimer.start(minutes)
    }

    fun stopTimer() {
        sleepTimer.stop()
    }

    // 排序规则的纯计算部分（收藏回填 + 默认顺序）：不触碰状态，
    // 供调用方在 IO 线程算好后回主线程赋值，避免大库排序占用主线程
    fun sortPlaylistForDefaultOrder(tracks: List<MusicTrack>): List<MusicTrack> =
        sortTracks(
            tracks.map { it.copy(isFavorite = it.id in likedIds) },
            PlaylistSortField.DEFAULT,
            descending = false,
        )

    // 按当前排序规则重排给定列表；规则为默认正序时原样返回，避免重复排序
    fun sortByActiveRule(tracks: List<MusicTrack>): List<MusicTrack> {
        if (playlistSortField == PlaylistSortField.DEFAULT && !playlistSortDescending) return tracks
        return sortTracks(tracks, playlistSortField, playlistSortDescending)
    }

    // 应用已排好序的播放列表，并保留当前曲目索引
    fun applySortedPlaylist(sorted: List<MusicTrack>) {
        val currentId = currentTrack?.id
        playlist = sorted
        currentIndex = sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(-1)
    }

    // 设置排序规则：仅默认全量播放列表套用重排（自定义/智能歌单保持自身顺序）；
    // 重排放后台线程执行，避免大库拼音排序占用主线程
    fun setPlaylistSort(field: PlaylistSortField, descending: Boolean) {
        playlistSortField = field
        playlistSortDescending = descending
        if (playlistSource == null) {
            val snapshot = playlist
            val revision = playlistRevision
            val source = playlistSource
            playbackScope.launch {
                val ordered = withContext(Dispatchers.Default) { sortTracks(snapshot, field, descending) }
                // 排序期间列表内容、歌单来源或排序规则已变：这份结果只代表旧快照，
                // 应用它会把新增/删除/改名的曲目一起回滚
                if (playlistRevision != revision ||
                    playlistSource != source ||
                    playlistSortField != field ||
                    playlistSortDescending != descending
                ) {
                    return@launch
                }
                applySortedPlaylist(ordered)
                persistPlaylist()
            }
        } else {
            persistPlaylist()
        }
    }

    // 切换指定曲目的收藏状态：仅就地更新收藏标记，不改变列表顺序。
    // 全量库备份须一并更新：面板浏览非播放队列的歌单时曲目取自备份，只改队列会让收藏图标不刷新。
    fun toggleFavorite(trackId: Long) {
        val newLiked = if (likedIds.contains(trackId)) likedIds - trackId else likedIds + trackId
        likedIds = newLiked
        val replace: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { if (it.id == trackId) it.copy(isFavorite = trackId in newLiked) else it }
        }
        playlist = replace(playlist)
        defaultPlaylistBackup = defaultPlaylistBackup?.let(replace)
        persistPlaylist()
    }

    // 按新顺序重排当前播放队列，保持当前曲目与播放索引同步
    fun reorderPlaylist(ordered: List<MusicTrack>) {
        if (ordered.isEmpty()) return
        val currentId = currentTrack?.id
        val tracks = ordered.map { it.copy(isFavorite = likedIds.contains(it.id)) }
        playlist = tracks
        currentIndex = tracks.indexOfFirst { it.id == currentId }
        persistPlaylist()
    }

    // 更新播放列表中指定曲目的元数据并持久化（列表、当前曲目与全量库备份同步替换）。
    // 备份必须一并替换：扫描刷新的缓存复用索引取自 libraryTracks（getter 优先返回备份），
    // 备份落后会在下次刷新时把旧字段（歌词等）搬回列表，抹掉刚写入的结果
    fun updateTrack(updated: MusicTrack) {
        val replace: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { if (it.id == updated.id) updated.copy(isFavorite = likedIds.contains(it.id)) else it }
        }
        playlist = replace(playlist)
        currentTrack = currentTrack?.let { if (it.id == updated.id) updated else it }
        defaultPlaylistBackup = defaultPlaylistBackup?.let(replace)
        persistPlaylist()
        // 通知栏与蓝牙读的是播放器里的 MediaItem：只改状态层列表不会刷新当前项，
        // 故把正在播放的同名项替换为最新标题/歌手/专辑；非当前项随下次装载自然更新
        if (currentTrack?.id == updated.id) {
            val snapshot = updated
            playbackScope.launch { refreshLoadedTrackMetadata(this@MusicPlaybackState, snapshot) }
        }
    }

    /**
     * 无损升级换 MediaStore ID 后迁移用户关联：收藏、最近播放与自定义歌单都按曲目 ID 记录，
     * 只更新音频 URI 而保留旧 ID，会在删旧条目后把这些关联一起丢掉。
     */
    suspend fun migrateTrackId(context: Context, oldId: Long, newId: Long) {
        if (oldId == newId) return
        if (oldId in likedIds) likedIds = (likedIds - oldId) + newId
        recentPlayedStore.migrateTrackId(oldId, newId)
        withContext(Dispatchers.IO) {
            playlistStore.awaitLoaded(context)
            playlistStore.replaceTrackId(context, oldId, newId)
        }
        persistPlaylist()
    }

    // 封面写入成功后自增，通知封面组件强制重载最新封面；
    // 同时作废索引曲目的系统略缩图缓存、非索引曲目的内嵌封面缓存、首页大封面缓存、背景取色缓存与
    // 落盘的背景取色结果、当前曲目的落盘封面，并解除「无封面可取」的跳过标记：旧图、旧色调与旧结论均已失效。
    //
    // 全部作废完成后才自增版本号：显示端的重新取图由版本号驱动，若把落盘档的清除丢到后台与版本号并发，
    // 重新取图会命中尚未删净的旧档（大封面的内存驻留被逐出后正好回落到这一档），封面停在旧图不再刷新。
    // 整段置于 NonCancellable：作废是「新封面已写入」的必然结论，不随调用方是否还在而取舍
    suspend fun bumpCoverRevision() {
        SystemThumbnailCache.clear()
        EmbeddedCoverCache.clear()
        CoverSkipRegistry.clear()
        CoverColorCache.clear()
        // 持久化的取色结果同样出自被重写的封面：一并作废，避免冷启动仍按旧色调铺底；
        // 新色调由显示端或切歌预取重取后重新落盘
        backgroundColors.clear()
        withContext(NonCancellable) {
            // 落盘封面同样是旧图：一并作废，避免冷启动拿旧封面顶出（新封面由下次切歌重新落盘）
            appContext?.let { context ->
                CurrentCoverCache.clear(context)
                LargeCoverStore.clear(context)
            }
        }
        coverRevision++
    }

    // 批量更新曲目元数据（封面等），一次触发重组；
    // 同时回写全量库备份，保证切歌单后其他歌单的歌曲引用到最新封面。
    // persist=false 时只更新内存态，由调用方按节流策略统一落盘（渐进补全用，
    // 使列表能逐条刷新而不必为每条回写整份播放列表）
    fun batchUpdateTracks(updates: List<MusicTrack>, persist: Boolean = true) {
        if (updates.isEmpty()) return
        val updateMap = updates.associateBy { it.id }
        val applyUpdates: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { orig ->
                updateMap[orig.id]?.let { it.copy(isFavorite = likedIds.contains(it.id)) } ?: orig
            }
        }
        playlist = applyUpdates(playlist)
        currentTrack = currentTrack?.let { updateMap[it.id] ?: it }
        defaultPlaylistBackup = defaultPlaylistBackup?.let(applyUpdates)
        if (persist) persistPlaylist()
    }

    // 按需补全单曲封面/歌词（懒加载）：幂等，由 UI 可见项触发，
    // 已具备缓存、已标记失败或在全量补全排期中的曲目自动跳过
    fun requestMetadata(track: MusicTrack?) {
        if (track == null) return
        val context = appContext ?: return
        playbackScope.launch {
            metadataEnricher.ensureMetadata(context, this@MusicPlaybackState, track)
        }
    }

    fun renameTrackMetadata(renamed: MusicTrack) {
        updateTrack(renamed)
    }

    // 微调歌词时间：stepMs 正值延后，负值提前（同时作用于逐字时间轴）
    fun adjustLyricsOffset(stepMs: Long) {
        val track = currentTrack ?: return
        if (track.lyricLines.isEmpty()) return
        val shifted = MusicMetadataCache.shiftLyrics(track.lyricLines, stepMs)
        updateTrack(track.copy(lyricLines = shifted, lyricOffsetMs = track.lyricOffsetMs + stepMs))
    }

    fun syncPlaybackState() {
        val controller = mediaController ?: return
        val playbackState = controller.playbackState
        val isActive = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
        val mediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
        val index = mediaId?.let { id -> playlist.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            currentIndex = index
            currentTrack = playlist[index]
        }
        syncPlaybackPosition(controller, isActive)
    }

    fun updatePosition() {
        val controller = mediaController ?: return
        val playbackState = controller.playbackState
        val isActive = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
        syncPlaybackPosition(controller, isActive)
        // 播放期间周期性持久化当前曲目与进度，避免冷启动/异常退出后丢失播放状态
        if (isActive && controller.isPlaying) {
            val now = System.currentTimeMillis()
            if (now - lastStatePersistAt >= STATE_PERSIST_INTERVAL_MS) {
                lastStatePersistAt = now
                persistState()
            }
            // 单曲循环播完回卷时计入一次完整播放（不受 onMediaItemTransition 触发与否影响）
            detectLoopRestart()
        }
    }

    // 单曲循环回卷检测：当前曲目播放位置从越过中部瞬间回退到开头即一次完整播放。
    // Media3 部分配置下 REPEAT_MODE_ONE 不投递 REPEAT 过渡回调，此处作为兜底收录，
    // 与过渡回调记录通过冷却去重，避免同一次循环计数两次
    private fun detectLoopRestart() {
        val track = currentTrack ?: return
        if (duration <= 0L) return
        // 以控制器原始位置检测回卷，避免被进度单调钳制掩盖导致单曲循环漏记
        val cur = mediaController?.currentPosition
            ?.coerceIn(0L, duration) ?: return
        val trackChanged = lastTickTrackId != track.id
        lastTickTrackId = track.id
        // 切歌后的首个 tick 仅建立基准，不判定
        if (trackChanged || lastTickPosition < 0L) {
            lastTickPosition = cur
            return
        }
        val prev = lastTickPosition
        lastTickPosition = cur
        if (prev - cur < LOOP_RESTART_MIN_JUMP_MS || prev <= duration / 2) return
        val now = System.currentTimeMillis()
        val deDuplicated = lastAutoCountTrackId == track.id &&
            now - lastAutoCountAtMs < AUTO_COUNT_COOLDOWN_MS
        if (deDuplicated) return
        lastAutoCountTrackId = track.id
        lastAutoCountAtMs = now
        // 回卷复位进度单调基准，让进度条回到起点
        lastMonoMediaId = null
        recordPlayed(track.id)
    }

    private fun syncPlaybackPosition(controller: MediaController, isActive: Boolean) {
        if (isActive) {
            val mediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
            val controllerDuration = controller.duration
            val controllerPosition = controller.currentPosition
            if (controllerPosition >= 0L && controllerDuration > 0L) {
                val raw = controllerPosition.coerceIn(0L, controllerDuration)
                // 播放器尚未 READY（冷启动续播/切歌预载阶段）时控制器可能短暂回报 0 或失真位置；
                // 若会显著回退当前已还原的进度则保留现状，避免续播瞬间进度条从还原位置清空到 0 再回填。
                // 真实切歌路径的 currentPosition 已被切歌回调复位到 0，不会误命中本守卫。
                if (!isPrepared && currentPosition > 0L &&
                    raw < currentPosition - MONO_REBASELINE_JUMP_MS
                ) {
                    isPlaying = controller.isPlaying
                    return
                }
                // 换项(mediaId 变化)或位置大幅回退（如切换歌单重载同 ID 曲目未触发切歌回调）时复位单调基准；
                // 小幅回退仍按流媒体回锚处理，保持进度单调，避免进度条倒退
                val reset = mediaId != lastMonoMediaId ||
                    raw < lastMonoPosition - MONO_REBASELINE_JUMP_MS
                if (reset) {
                    lastMonoMediaId = mediaId
                    lastMonoPosition = raw
                    duration = controllerDuration
                    currentPosition = raw
                } else {
                    duration = maxOf(duration, controllerDuration)
                    currentPosition = maxOf(lastMonoPosition, raw).also { lastMonoPosition = it }
                }
            }
        }
        isPlaying = controller.isPlaying
    }

    private fun calculateIndex(direction: Int, repeatOne: Boolean, from: Int = currentIndex): Int {
        if (playlist.isEmpty()) return -1
        // 当前曲目不在队列内（如切换歌单后正在播放的曲目已不属于新歌单）：
        // 下一首取队列首曲、上一首取队尾，而非把缺失下标当作首曲再顺延一首
        if (from !in playlist.indices) {
            return if (direction < 0) playlist.lastIndex else 0
        }
        return when {
            playMode == PlayMode.RepeatOne && repeatOne -> from
            playMode == PlayMode.Shuffle -> {
                if (playlist.size == 1) 0
                else playlist.indices.filter { it != from }.random()
            }
            direction < 0 -> (from - 1 + playlist.size) % playlist.size
            else -> (from + 1) % playlist.size
        }
    }

    private fun autoNextIndex(): Int = calculateIndex(direction = 1, repeatOne = true)

    fun nextIndex(): Int = calculateIndex(direction = 1, repeatOne = false)

    fun previousIndex(): Int = calculateIndex(direction = -1, repeatOne = false)

    /**
     * 当前是否有 USB 输出开关处于启用。
     *
     * 直出与独占都属沉浸式输出（整曲保真聆听），与「把每首裁成 30–45 秒片段」的心动模式取向相斥，
     * 故任一开关启用即不接受心动模式。
     */
    val immersiveOutputActive: Boolean
        get() = isImmersiveOutput(usbDirectOutputEnabled, usbExclusiveEnabled)

    // ===== UI 层状态写入口：悬浮窗 UI 统一通过这些方法写入状态，避免直接对 public var 赋值 =====
    // 方法与属性 setter 同名会冲突，故用 @JvmName 指定不同 JVM 名
    @JvmName("updatePlayMode")
    fun setPlayMode(mode: PlayMode) {
        if (playMode == mode) return
        // 沉浸式输出期间不接受心动模式：界面据此说明缘由而不是静默不响应
        if (mode == PlayMode.Highlight && immersiveOutputActive) {
            highlightExitNotice = HighlightExitReason.ImmersiveOutput
            return
        }
        val previous = playMode
        playMode = mode
        highlightSkipStreak = 0
        // 只有跨越心动模式边界的切换才改变队列项：进入时整队列补上裁剪区间，退出时整队列去掉。
        // 非心动各模式互切不动队列（repeatMode/shuffleMode 由调用方同步），此时不得重建 ——
        // 重建会被判成「队列已变」而重载整条时间线，表现为切模式时当前曲目卡顿
        if (previous != PlayMode.Highlight && mode != PlayMode.Highlight) return
        // 退出片段播放即刻撤下音量斜坡：淡出末尾的低音量会随整曲播放一直延续到下一次轮询
        if (mode != PlayMode.Highlight) mediaController?.volume = 1f
        // 队列项随模式变化，缓存作废；下面重建后会写回
        cachedMediaItems = null
        val context = appContext ?: return
        // 片段取自持久化表，切换只做查表与重建队列、不含任何解析；
        // 当前曲目一并生效：进入自片段起点起播，退出还原到整曲的绝对位置
        applyHighlightModeChange(context, this)
        // 表可能尚未扫完（首次启用、曲库刚变化）：后台补齐，已扫到的先按现有结果播放
        if (mode == PlayMode.Highlight) ensureHighlightScan(context)
    }

    /**
     * 输出成色变化的收尾：成立直出即退出心动模式。
     *
     * 直出与片段播放的取向相斥：直出为整曲保真聆听服务，无需把每首裁成 30–45 秒的片段。
     * （片段的音量淡入淡出在本应用采用的源格式直出档下是生效的——该档按常规链路处理音轨音量——
     * 故互斥的判据是取向，不是淡入淡出能否实施。）
     *
     * 成色回调来自播放线程与主线程两处，故统一切到主线程再改播放模式。
     */
    /**
     * USB 输出开关变化：一经启用即退出心动模式。
     *
     * 开关可以在曲中才被拨动，此时模式不能就这么留在片段播放上——片段首尾的音量淡入淡出在沉浸链路上
     * 无从实施，队列项也仍是裁过的那些。调用方须先写好开关状态再调本方法：判据读的是当前状态。
     */
    fun onImmersiveOutputChanged() {
        if (!immersiveOutputActive || playMode != PlayMode.Highlight) return
        playbackScope.launch {
            if (playMode != PlayMode.Highlight) return@launch
            highlightExitNotice = HighlightExitReason.ImmersiveOutput
            setPlayMode(PlayMode.RepeatAll)
        }
    }

    /**
     * 后台扫描全库副歌片段并落盘。
     *
     * 播放路径只查表：扫描在这里一次做完，切模式与切歌都不再解析，既不卡顿也不重复计算。
     * 只处理未扫过或歌词已变的曲目，重复调用无代价。启动恢复完成、曲库变化、启用心动模式三处触发。
     *
     * 定位为纯歌词分析（不解码音频），故扫描与当前模式无关：无论是否处于心动模式都只读歌词。
     */
    fun ensureHighlightScan(context: Context) {
        if (highlightScanJob?.isActive == true) return
        val tracks = libraryTracks
        if (tracks.isEmpty()) return
        highlightScanJob = playbackScope.launch {
            try {
                val updated = HighlightScanner.scan(context, tracks)
                // 表变了就把新片段补进已装载的队列：队列可能先于扫描装载，那一版还是整曲项
                if (updated && playMode == PlayMode.Highlight) {
                    applyNewClipsToLoadedQueue(this@MusicPlaybackState)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogManager.logException("MusicPlaybackState", "扫描副歌片段失败", e)
            }
        }
    }
    @JvmName("updateSearchMode")
    fun setSearchMode(enabled: Boolean) { isSearchMode = enabled }
    @JvmName("updateSearchResultsVisible")
    fun setSearchResultsVisible(visible: Boolean) { showSearchResults = visible }
    @JvmName("updateSearchQuery")
    fun setSearchQuery(query: String) { searchQuery = query }
    @JvmName("updateSearchSource")
    fun setSearchSource(source: MusicSearchSource) { searchSource = source }
    @JvmName("updateLyricsVisible")
    fun setLyricsVisible(visible: Boolean) { isLyricsVisible = visible }
    @JvmName("updateLocalCoverCandidates")
    fun setLocalCoverCandidates(candidates: List<RecentCover>) { localCoverCandidates = candidates }
    @JvmName("updateCoverCandidates")
    fun setCoverCandidates(candidates: List<NeteaseSongSearchResult>) { coverCandidates = candidates }
    @JvmName("updateLyricsCandidates")
    fun setLyricsCandidates(candidates: List<NeteaseSongSearchResult>) { lyricsCandidates = candidates }
    @JvmName("updateLyricsRefreshError")
    fun setLyricsRefreshError(error: String?) { lyricsRefreshError = error }
    @JvmName("updateLyricsRefreshSource")
    fun setLyricsRefreshSource(source: MusicSearchSource) { lyricsRefreshSource = source }
    @JvmName("updateCoverRefreshSource")
    fun setCoverRefreshSource(source: MusicSearchSource) { coverRefreshSource = source }
    @JvmName("updateErrorMsg")
    fun setErrorMsg(message: String?) { errorMsg = message }
    @JvmName("updateTimerMinutes")
    fun setTimerMinutes(minutes: Int) {
        sleepTimer.setMinutes(minutes)
    }
    @JvmName("updateCurrentPosition")
    fun setCurrentPosition(position: Long) {
        // 拖动进度条直接改写位置：复位单调基准，避免被钳回拖动前的位置
        lastMonoMediaId = null
        currentPosition = position
        // 拖动是真实跳变：作废歌词时间轴快照，歌词不沿用拖动前的进度
        invalidateLyricTimeline()
    }

    // 上次补扫副歌片段所依据的曲库签名：批量下载会逐首触发曲库变更，据此去重，不为每首各扫一遍
    private var scannedLibrary: Set<Long> = emptySet()

    /**
     * 本地曲库变化（缓存 / 下载入库）后同步派生数据。
     *
     * 新入库的歌还没有副歌片段，补一次扫描（只处理指纹过期的曲目）。
     */
    internal fun onLibraryChanged() {
        if (librarySignature() == scannedLibrary) return
        scannedLibrary = librarySignature()
        val context = appContext ?: return
        ensureHighlightScan(context)
    }

    /** 本地曲库的身份快照：曲目增删都会改变它 */
    private fun librarySignature(): Set<Long> = libraryTracks.mapTo(HashSet()) { it.id }

    /**
     * 心动模式下曲目落定后的收尾：不可定位副歌的曲目直接跳过。
     *
     * 跳过带回绕保护：整队列都定位不到时（纯音乐歌单、歌词全无），连续跳过会绕回原点空转，
     * 故连续跳过数超过队列长度即退出该模式交回普通播放，并置一次性提示供界面说明原因。
     *
     * 只有「已扫描、有歌词、且判定无副歌」才跳过。歌词尚未就绪不等于没有副歌 ——
     * 据此跳过会在冷启动时把整库都跳过，故未就绪时照常整曲播放，等扫描补齐后即是片段。
     */
    private fun settleHighlightOnTrackChange() {
        if (playMode != PlayMode.Highlight) {
            highlightSkipStreak = 0
            return
        }
        val context = appContext ?: return
        val track = currentTrack ?: return
        if (!isKnownHighlightMiss(track)) {
            highlightSkipStreak = 0
            return
        }
        if (highlightSkipStreak >= playlist.size) {
            highlightSkipStreak = 0
            highlightExitNotice = HighlightExitReason.NoChorus
            setPlayMode(PlayMode.RepeatAll)
            return
        }
        highlightSkipStreak++
        val next = nextIndex()
        if (next in playlist.indices && next != currentIndex) {
            playbackScope.launch {
                playTrackAt(
                    context,
                    this@MusicPlaybackState,
                    next,
                    clearQueue = false,
                    switchKind = TrackSwitchKind.Next,
                )
            }
        }
    }
}

/**
 * 心动模式自行退出的原因：界面据此说明模式为何自己变了，而非让用户以为失灵。
 *
 * 与「用户主动切换」区分开 —— 只有自行退出才需要解释缘由。
 */
enum class HighlightExitReason {
    /** 整队列都定位不到副歌，继续跳过只会回绕空转 */
    NoChorus,

    /**
     * USB 输出开关启用：取向与片段播放相斥，片段模式随之退出。
     *
     * 直出与独占共用一个缘由：两者的取向完全一致（整曲保真、不裁片段），差别只在实现——一个走平台的
     * 专用输出流，一个自己接管接口——提示语里一并说明即可，不必分成两条。
     */
    ImmersiveOutput,
}

/**
 * 两个 USB 输出开关中是否有任一启用。
 *
 * 判据取开关而不是输出成色：成色要等设备接入、属性与格式被受理之后才成立，而用户的取向在拨动开关那一刻
 * 就定了；等成色成立才挡，中间那段时间里心动模式仍能进入。开关互斥（见 `PlaybackPrefs`），但此处不依赖
 * 那一点——两个都传进来，任一为真即算。
 */
internal fun isImmersiveOutput(usbDirectOutputEnabled: Boolean, usbExclusiveEnabled: Boolean): Boolean =
    usbDirectOutputEnabled || usbExclusiveEnabled
