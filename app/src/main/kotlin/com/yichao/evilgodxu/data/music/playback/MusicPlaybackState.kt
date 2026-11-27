package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.playlist.albumIdentity
import kotlin.jvm.JvmName
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MusicPlaybackState(
    val appContext: Context,
    private val persistenceScope: CoroutineScope,
    private val metadataEnricher: MetadataEnricher,
    internal val playlistStore: PlaylistStore,
    internal val proxySourceStore: ProxySourceStore,
) {

    companion object {
        // 定时关闭步长与上下限（分钟）
        const val TIMER_STEP_MINUTES = 5
        const val TIMER_MIN_MINUTES = 5
        const val TIMER_MAX_MINUTES = 999
    }

    // 冷启动恢复任务去重，多个调用方共享同一任务
    private val restoreMutex = Mutex()
    private var restoreJob: Deferred<Unit>? = null
    var mediaController: MediaController? by mutableStateOf(null)
    var player: Player? by mutableStateOf(null)
    internal var suppressAutoNext = false
    // 播放器事件在此分发
    val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            handleMediaItemTransition(this@MusicPlaybackState, mediaItem, reason)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            handlePositionDiscontinuity(this@MusicPlaybackState, reason)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            handlePlaybackStateChanged(this@MusicPlaybackState, playbackState)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            handlePlayerError(this@MusicPlaybackState)
        }
    }
    var isPlaying by mutableStateOf(false)
    var isPrepared by mutableStateOf(false)
    val isPlayerActive: Boolean
        get() = mediaController?.let { ctrl ->
            ctrl.isPlaying || ctrl.playbackState == Player.STATE_BUFFERING
        } ?: false
    var duration: Long
        get() = progress.duration
        set(value) { progress.duration = value }
    var currentPosition: Long
        get() = progress.currentPosition
        set(value) { progress.currentPosition = value }

    // 进度换算由进度跟踪器持有
    val livePositionMs: Long get() = progress.livePositionMs
    val highlightPositionOffsetMs: Long get() = progress.highlightPositionOffsetMs
    val lyricPositionMs: Long get() = progress.lyricPositionMs

    fun toPlayerPosition(absoluteMs: Long): Long = progress.toPlayerPosition(absoluteMs)

    private val persistablePositionMs: Long get() = progress.persistablePositionMs

    private val _playlist = mutableStateOf<List<MusicTrack>>(emptyList())
    // 列表版本号，后台排序据此丢弃过期快照
    internal var playlistRevision = 0
    var playlist: List<MusicTrack>
        get() = _playlist.value
        set(value) {
            _playlist.value = value
            playlistRevision++
            cachedMediaItems = null
            // 无过滤时默认队列与播放队列同源
            if (queueFilter == null) defaultPlaylist = value
        }
    /** 缓存的 MediaItem 列表，避免重复构建 */
    var cachedMediaItems by mutableStateOf<List<androidx.media3.common.MediaItem>?>(null)
    var currentIndex by mutableIntStateOf(-1)
    var currentTrack by mutableStateOf<MusicTrack?>(null)

    // 最近一次切换类型，须与曲目状态同帧写入
    var lastSwitchKind by mutableStateOf(TrackSwitchKind.Select)
        private set
    var playMode by mutableStateOf(PlayMode.RepeatAll)
    var errorMsg by mutableStateOf<String?>(null)
    var isScanning by mutableStateOf(false)
    // 元数据补全进行中，曲库分析据此让路
    var isEnrichingMetadata by mutableStateOf(false)
    var isLyricsVisible by mutableStateOf(false)

    // 搜索态转发 SearchPanelState
    internal val searchPanel = SearchPanelState()

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
    var lyricsCandidates by searchPanel::lyricsCandidates
    var isLyricsSearching by searchPanel::isLyricsSearching
    var isLyricsRefreshing by searchPanel::isLyricsRefreshing
    var lyricsRefreshError by searchPanel::lyricsRefreshError
    var lyricsRefreshSource by searchPanel::lyricsRefreshSource
    var coverRefreshSource by searchPanel::coverRefreshSource
    var metadataFillCandidates by searchPanel::metadataFillCandidates
    var isMetadataFillSearching by searchPanel::isMetadataFillSearching
    var metadataFillSource by searchPanel::metadataFillSource

    // 心动模式：三个独立组件分担扫描与策略

    suspend fun removeUnavailableExternalTracks(context: Context) {
        playlistRemoval.removeUnavailableExternalTracks(context)
    }

    // 缓存下载中的曲目，切歌清理时保留
    val cacheInProgressIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    fun removeTrack(trackId: Long, advanceToNext: Boolean = false) {
        playlistRemoval.remove(trackId, advanceToNext)
    }

    suspend fun deleteSongPermanently(context: Context, track: MusicTrack) {
        playlistRemoval.deleteSongPermanently(context, track)
    }

    // 音频链路状态归 audioSignal
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

    // volatile 在持有方生效，此处只转发
    var audioTrack: AudioTrack?
        get() = audioSignal.audioTrack
        set(value) { audioSignal.audioTrack = value }
    var audioSinkWrittenFrames by audioSignal::audioSinkWrittenFrames
    var usbExclusiveLatency by audioSignal::usbExclusiveLatency
    // 直出成色归 audioSignal
    var directOutputMode by audioSignal::directOutputMode

    var usbExclusiveRoute by audioSignal::usbExclusiveRoute

    var usbExclusiveDevice by audioSignal::usbExclusiveDevice

    var usbExclusiveClaimed by audioSignal::usbExclusiveClaimed

    var exclusiveVolumeStep by audioSignal::exclusiveVolumeStep
    var setExclusiveVolumeStep by audioSignal::setExclusiveVolumeStep

    var usbDirectOutputEnabled by audioSignal::usbDirectOutputEnabled
    var usbExclusiveEnabled by audioSignal::usbExclusiveEnabled

    var likedIds by mutableStateOf<Set<Long>>(emptySet())

    internal val recentPlayedStore = RecentPlayedStore(
        context = appContext,
        scope = persistenceScope,
    )
    internal val searchHistoryStore = SearchHistoryStore(
        context = appContext,
        scope = persistenceScope,
    )
    // 写协程在主线程，待写值只由界面改，无需加锁
    internal val searchSourceStore = SearchSourceStore(
        scope = { playbackScope },
        context = { appContext },
    )
    private val playlistPersistence = PlaylistPersistence(
        context = appContext,
        scope = persistenceScope,
    )
    internal val startupStateLoader = StartupStateLoader(
        searchHistoryStore = searchHistoryStore,
        recentPlayedStore = recentPlayedStore,
        playlistPersistence = playlistPersistence,
        proxySourceStore = proxySourceStore,
    )
    // 冷启动恢复由 PlaybackRestorer 编排
    private val restorer by lazy { PlaybackRestorer(this) }
    internal val backgroundColors = BackgroundColorMemory(
        scope = { playbackScope },
        context = { appContext },
        currentTrackUri = { currentTrack?.audioUri },
        onCurrentTrackColorsChanged = { persistBootMirror() },
    )
    internal val artworkCache = ArtworkCacheController(
        scope = { playbackScope },
        context = { appContext },
        currentTrack = { currentTrack },
        nextTrack = { adjacentPlaybackTrack(forward = true) },
        previousTrack = { adjacentPlaybackTrack(forward = false) },
        libraryTracks = { libraryTracks },
        colorMemory = backgroundColors,
    )

    /** 相邻曲目：随机按 next/previous，插队优先 */
    private fun adjacentPlaybackTrack(forward: Boolean): MusicTrack? {
        val controller = mediaController ?: return null
        if (forward) {
            playNext.tracks.firstOrNull()?.let { return it }
        }
        val index = if (forward) controller.nextMediaItemIndex else controller.previousMediaItemIndex
        return playlist.getOrNull(index)
    }
    // 封面缓存作废归 coverCache
    internal val coverCache = CoverCacheInvalidator(
        context = { appContext },
        colorMemory = backgroundColors,
    )
    // 封面写入后自增，触发强制重载
    var coverRevision by coverCache::coverRevision
    internal val highlightScan = HighlightScanCoordinator(
        scope = { playbackScope },
        context = { appContext },
        playMode = { playMode },
        libraryTracks = { libraryTracks },
        onClipsUpdated = { applyNewClipsToLoadedQueue(this@MusicPlaybackState) },
    )
    internal val highlightPolicy = HighlightPlaybackPolicy(
        scope = { playbackScope },
        context = { appContext },
        playMode = { playMode },
        currentTrack = { currentTrack },
        playlistSize = { playlist.size },
        currentIndex = { currentIndex },
        nextIndex = { nextIndex() },
        playAt = { context, index ->
            playbackScope.launch {
                playTrackAt(
                    context,
                    this@MusicPlaybackState,
                    index,
                    clearQueue = false,
                    switchKind = TrackSwitchKind.Next,
                )
            }
        },
        exitToNormal = { setPlayMode(PlayMode.RepeatAll) },
        immersiveOutputActive = { immersiveOutputActive },
    )
    // 心动模式自行退出的原因，消费后清空
    var highlightExitNotice by highlightPolicy::exitNotice
    // ReplayGain：按本曲标签调整播放器音量
    private var highlightFadeRef: HighlightFadeController? = null
    internal val replayGainVolume = ReplayGainVolume(
        scope = { playbackScope },
        context = { appContext },
        currentTrack = { currentTrack },
        onApplied = { highlightFadeRef?.syncFadeVolume() },
    )
    internal val highlightFade = HighlightFadeController(
        playMode = { playMode },
        currentTrack = { currentTrack },
        mediaController = { mediaController },
        replayGain = { replayGainVolume.linear },
    ).also { highlightFadeRef = it }

    /** 把音量增益开关落到运行时，当前曲目立即生效 */
    internal fun setVolumeGainEnabled(enabled: Boolean) {
        replayGainVolume.applyEnabled(enabled)
    }

    internal val progress = PlaybackProgressTracker(
        scope = { playbackScope },
        controller = { mediaController },
        currentTrack = { currentTrack },
        isPrepared = { isPrepared },
        isPlaying = { isPlaying },
        onPeriodicPersist = { persistState() },
        highlight = highlightFade,
        recentPlayedStore = recentPlayedStore,
    )

    // 回卷检测与进度持久化由进度跟踪器持有

    val recentPlayedIds: List<Long> get() = recentPlayedStore.ids

    // 默认队列过滤来源，null 为不过滤
    var queueFilter by mutableStateOf<PlaylistSource?>(null)
    // 面板浏览态：true 显示队列，false 显示来源
    var viewedFollowsQueue by mutableStateOf(true)
        private set
    // 面板浏览的歌单来源，null 为默认列表
    var viewedSource by mutableStateOf<PlaylistSource?>(null)
        private set
    // 排序规则，仅作用于默认全量列表
    var playlistSortField by mutableStateOf(PlaylistSortField.DEFAULT)
    var playlistSortDescending by mutableStateOf(false)
    // 默认播放队列本体，过滤只作用于播放结果
    var defaultPlaylist by mutableStateOf<List<MusicTrack>>(emptyList())
    // 全量库，过滤期间仍保持完整
    val libraryTracks: List<MusicTrack>
        get() = defaultPlaylist

    // 浏览歌单只改面板展示，不动播放队列
    fun viewPlaylist(source: PlaylistSource?) {
        if (source == null && queueFilter == null) {
            followPlaybackQueue()
            return
        }
        viewedFollowsQueue = false
        viewedSource = source
        persistPlaylist()
    }

    // 恢复面板浏览态：无过滤时浏览默认等价于跟随队列
    internal fun applyRestoredViewedState(followsQueue: Boolean, source: PlaylistSource?) {
        viewedFollowsQueue = followsQueue || (source == null && queueFilter == null)
        viewedSource = if (viewedFollowsQueue) null else source
    }

    fun followPlaybackQueue() {
        if (viewedFollowsQueue) return
        viewedFollowsQueue = true
        viewedSource = null
        persistPlaylist()
    }

    // 记录完整播放，并清理超出窗口的旧记录
    fun recordPlayed(trackId: Long) {
        recentPlayedStore.record(trackId)
    }

    // 手动移除常听记录，期间不再自动收录
    fun removeFromRecentPlayed(trackId: Long) {
        recentPlayedStore.remove(trackId)
    }

    // 插队队列由 PlayNextQueue 持有，此处转发
    internal val playNext = PlayNextQueue(currentTrackId = { currentTrack?.id })
    var playNextQueue by playNext::tracks

    // 曲末暂停开关的下发口，队列非空即停在曲末等接管
    internal var playNextHoldChanged: ((Boolean) -> Unit)? = null

    internal fun syncPlayNextHold() {
        playNextHoldChanged?.invoke(playNext.tracks.isNotEmpty())
    }
    // 队列编辑由 PlaylistEditor 承担
    internal val playlistEditor by lazy { PlaylistEditor(this) }
    // 曲目移除交给移除控制器
    internal val playlistRemoval by lazy { PlaylistRemovalController(this) }
    // 待接入队列起播位置，null 为无；等当前曲播完再接
    internal var pendingQueueStartIndex: Int? = null

    // 曲目是否已在下一首播放队列
    fun isInPlayNext(trackId: Long): Boolean = playNext.isIn(trackId)

    // 切换下一首播放：在队列则取消，否则加入
    fun togglePlayNext(track: MusicTrack) {
        playNext.toggle(track)
        syncPlayNextHold()
    }

    // 手动切歌时清空插队队列
    fun clearPlayNextQueue() {
        playNext.clear()
        syncPlayNextHold()
    }

    /** 记下本次曲目切换类型，须与曲目状态同帧写入 */
    internal fun beginTrackSwitch(kind: TrackSwitchKind) {
        lastSwitchKind = kind
    }

    // 定时关闭归 sleepTimer
    internal val sleepTimer = SleepTimerController(
        isPlaying = { isPlaying },
        playMode = { playMode },
        mediaController = { mediaController },
        onCountdownFinished = ::onSleepTimerCountdownFinished,
    )
    var timerMinutes by sleepTimer::timerMinutes
    var timerRemaining by sleepTimer::timerRemaining
    var onSleepTimerFinished by sleepTimer::onSleepTimerFinished

    // 播放控制作用域，用于自动下一首
    private val playbackJob = SupervisorJob()
    val playbackScope = CoroutineScope(playbackJob + Dispatchers.Main)

    // 歌词时间轴由 LyricTimeline 持有
    internal val lyricTimeline = LyricTimeline()

    fun seedLyricPosition(trackId: Long?): Long {
        // 心动模式回报片段内进度，换算回整曲时间
        return lyricTimeline.seed(trackId, lyricPositionMs)
    }

    // 每轮回写当前进度，供界面重建续推
    fun recordLyricPosition(trackId: Long?, positionMs: Long) {
        lyricTimeline.record(trackId, positionMs)
    }
    // 防止手动与自动切歌并发
    val playTrackMutex = Mutex()

    val hasTrack: Boolean get() = currentTrack != null

    suspend fun restoreSavedState(context: Context) {
        // 首个调用方恢复，其余共享同一任务
        val job = restoreMutex.withLock {
            restoreJob ?: playbackScope.async { doRestoreSavedState(context.applicationContext) }
                .also { restoreJob = it }
        }
        job.await()
    }

    private suspend fun doRestoreSavedState(context: Context) {
        // 读取与应用由 PlaybackRestorer 承担
        restorer.restore(context)
    }

    // 源格式预读归 audioSignal
    fun recordSourceBitDepthBeforePlayback(track: MusicTrack) =
        audioSignal.recordSourceBitDepthBeforePlayback(track)

    /** 按 id 取已知源位深；换曲重配时当前曲目尚未推进 */
    fun bitDepthOfTrack(trackId: Long): Int? = audioSignal.bitDepthOfTrack(trackId)

    // 预读源格式入档，供配置期定档
    fun preloadSourceFormat(track: MusicTrack) = audioSignal.preloadSourceFormat(track)

    // 未播放时预读当前曲目格式，供信息条展示
    fun refreshIdleTrackFormatInfo(context: Context) =
        audioSignal.refreshIdleTrackFormatInfo(context)

    // 缓存完成后以本地文件补齐格式信息
    fun refreshTrackFormatInfoFromLocal(context: Context) =
        audioSignal.refreshTrackFormatInfoFromLocal(context)

    // 回前台校正音频信息，错配则重读
    fun reconcileTrackFormatInfo(context: Context) =
        audioSignal.reconcileTrackFormatInfo(context)

    // 格式信息是否对应当前曲目与音源
    val isAudioSignalPathCurrent: Boolean
        get() = audioSignal.isCurrentForCurrentTrack

    /** 当前曲目源位深，归属不符视为未读到 */
    val currentSourceBitDepth: Int?
        get() = audioSignalPathFormat?.takeIf { isAudioSignalPathCurrent }?.bitDepth

    fun persistPlaylist() {
        // 偏好合并后一次落盘
        playlistPersistence.persist(
            PlaylistPersistence.Snapshot(
                tracks = playlist,
                source = queueFilter,
                defaultPlaylist = defaultPlaylist,
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
    // 背景取色归 backgroundColors
    fun restoredBackgroundColorFor(track: MusicTrack?): Color? =
        backgroundColors.restoredColorFor(track)?.representative

    // 续播锚点：-1 表示真实切歌，过渡回调复位进度
    internal var resumeAnchorPosition: Long = -1L

    // 锚点归属曲目；无损升级换源时防旧回调提前消费
    internal var resumeAnchorTrackId: Long = -1L

    fun persistState() {
        val track = currentTrack ?: return
        // 位置取整曲绝对位置，心动片段须换算
        playbackStateStore.persist(track.audioUri, persistablePositionMs, playMode.ordinal)
        persistBootMirror()
    }

    // 播放状态快照归 playbackStateStore
    internal val playbackStateStore = PlaybackStateStore(
        scope = { playbackScope },
        context = { appContext },
    )
    // 启动镜像：轻量副本只服务首帧
    private val bootMirrorStore = BootMirrorStore(
        scope = { playbackScope },
        context = { appContext },
    )

    /** 是否有可预置的启动镜像；首启动没有 */
    fun hasBootMirror(context: Context): Boolean = bootMirrorStore.has(context)

    /** 落盘本轮扫描的曲库签名 */
    suspend fun writeLibrarySignature(signature: String) {
        playlistPersistence.writeLibrarySignature(signature)
    }

    /** 上次扫描的曲库签名，空表示从未扫完 */
    suspend fun readLibrarySignature(): String? = playlistPersistence.readLibrarySignature()

    /** 同步读取启动镜像并预置首帧状态，只可在冷启动主线程调用一次 */
    fun seedFromBootMirror(context: Context) {
        val snapshot = bootMirrorStore.read(context) ?: return
        val track = snapshot.track
        // 歌词随首帧读出，须与补全路径一致应用手动偏移
        val lines = track.lyricCachePath
            .takeIf { MusicMetadataCache.isValid(context, it) }
            ?.let { MusicMetadataCache.loadLyrics(context, it) }
            ?.let { if (track.lyricOffsetMs != 0L) MusicMetadataCache.shiftLyrics(it, track.lyricOffsetMs) else it }
            .orEmpty()
        // 封面位图同步驻留，避免占位符
        CurrentCoverCache.loadBlocking(context, track.audioUri)
        // 首帧列表未恢复，下标先置 -1
        currentIndex = -1
        currentTrack = track.copy(lyricLines = lines)
        currentPosition = snapshot.position
        duration = track.duration
        playMode = PlayMode.entries.getOrElse(snapshot.modeOrdinal) { PlayMode.RepeatAll }
        snapshot.colorUri?.let { backgroundColors.adopt(it, snapshot.colors) }
        pendingSavedUri = track.audioUri
        pendingResumePosition = currentPosition
    }

    // 同步落盘启动镜像，供下次首帧取用
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

    // 播放器空闲时才真正释放
    fun releaseIfIdle() {
        if (!isPlayerActive) softRelease()
    }

    fun softRelease() {
        progress.stopTicker()
        persistState()
        currentTrack?.let { track ->
            pendingSavedUri = track.audioUri
            pendingResumePosition = persistablePositionMs
        }
        // 清掉未消费的续播锚点
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
        // 释放后复位单调基准
        progress.resetMonotonicBaseline()
    }

    fun release() {
        progress.stopTicker()
        persistState()
        currentTrack?.let { track ->
            pendingSavedUri = track.audioUri
            pendingResumePosition = persistablePositionMs
        }
        // 清掉未消费的续播锚点
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
        // 进度归零并作废歌词时间轴
        progress.reset()
        lyricTimeline.invalidate()
        isPlaying = false
        isPrepared = false
        errorMsg = null
        stopTimer()
    }

    // 定时关闭收尾：播完落到下一首，退出前先落盘
    private fun onSleepTimerCountdownFinished(songFinished: Boolean) {
        if (songFinished) adoptNextTrackAfterFinish()
        release()
        playbackScope.launch {
            // 退出会终止进程，先把续播目标落盘
            playbackStateStore.inFlightWrite?.join()
            bootMirrorStore.inFlightWrite?.join()
            onSleepTimerFinished?.invoke()
        }
    }

    // 当前曲目落到下一首，列表仅一首时不动
    private fun adoptNextTrackAfterFinish() {
        val next = nextIndex()
        val track = playlist.getOrNull(next) ?: return
        if (track.id == currentTrack?.id) return
        currentIndex = next
        currentTrack = track
        replayGainVolume.onTrackChanged()
        currentPosition = 0L
        duration = track.duration
        isPlaying = false
        isPrepared = false
    }

    // 启动定时关闭（分钟）：播完当前整曲即停并退出
    fun startTimer(minutes: Int) {
        sleepTimer.start(minutes)
    }

    fun stopTimer() {
        sleepTimer.stop()
    }

    // 编辑操作由 PlaylistEditor 承担
    fun sortPlaylistForDefaultOrder(tracks: List<MusicTrack>): List<MusicTrack> =
        playlistEditor.sortForDefaultOrder(tracks)

    fun sortByActiveRule(tracks: List<MusicTrack>): List<MusicTrack> =
        playlistEditor.sortByActiveRule(tracks)

    fun applyDefaultPlaylist(sorted: List<MusicTrack>) {
        playlistEditor.applyDefaultPlaylist(sorted)
    }

    fun setPlaylistSort(field: PlaylistSortField, descending: Boolean) {
        playlistEditor.setSort(field, descending)
    }

    fun toggleFavorite(trackId: Long) {
        playlistEditor.toggleFavorite(trackId)
    }

    fun reorderPlaylist(ordered: List<MusicTrack>) {
        playlistEditor.reorder(ordered)
    }

    /** 替换曲目元数据并重绑专辑身份，避免曲目留在旧专辑 */
    fun updateTrack(updated: MusicTrack) {
        playlistEditor.updateTrack(withAlbumIdentity(updated))
    }

    // 专辑名变过才重绑，改前名字取自库内当前值
    private fun withAlbumIdentity(updated: MusicTrack): MusicTrack {
        val current = libraryTracks.firstOrNull { it.id == updated.id } ?: return updated
        if (current.albumName.trim() == updated.albumName.trim()) return updated
        return updated.copy(albumId = albumIdentity(libraryTracks, updated.albumName))
    }

    suspend fun migrateTrackId(oldId: Long, newId: Long) {
        playlistEditor.migrateTrackId(oldId, newId)
    }

    fun batchUpdateTracks(updates: List<MusicTrack>, persist: Boolean = true) {
        playlistEditor.batchUpdate(updates, persist)
    }

    // 按需补全单曲封面/歌词，幂等且可见项触发
    fun requestMetadata(track: MusicTrack?) {
        if (track == null) return
        playbackScope.launch {
            metadataEnricher.ensureMetadata(appContext, this@MusicPlaybackState, track)
        }
    }

    fun renameTrackMetadata(renamed: MusicTrack) {
        updateTrack(renamed)
    }

    // 微调歌词时间，正值延后
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
        replayGainVolume.onTrackChanged()
        progress.syncFromController(isActive)
        // 播放意图只由播放器事件维护，轮询不回写
        isPlaying = controller.isPlaying
    }

    internal fun calculateIndex(direction: Int, repeatOne: Boolean, from: Int = currentIndex): Int {
        if (playlist.isEmpty()) return -1
        // 当前曲目不在队列内时，下一首取首曲、上一首取末曲
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

    internal fun autoNextIndex(): Int = calculateIndex(direction = 1, repeatOne = true)

    fun nextIndex(): Int = calculateIndex(direction = 1, repeatOne = false)

    fun previousIndex(): Int = calculateIndex(direction = -1, repeatOne = false)

    /** 是否有任一 USB 输出开关启用；与心动片段模式相斥 */
    val immersiveOutputActive: Boolean
        get() = isImmersiveOutput(usbDirectOutputEnabled, usbExclusiveEnabled)

    // UI 状态写入口，@JvmName 避开 setter
    @JvmName("updatePlayMode")
    fun setPlayMode(mode: PlayMode) {
        if (playMode == mode) return
        // 沉浸式输出期间拒绝心动模式
        if (mode == PlayMode.Highlight && immersiveOutputActive) {
            highlightPolicy.notifyImmersiveBlocked()
            return
        }
        val previous = playMode
        playMode = mode
        highlightPolicy.onModeChanged()
        // 仅跨越心动模式边界时重建队列，避免切模式卡顿
        if (previous != PlayMode.Highlight && mode != PlayMode.Highlight) return
        // 退出片段即刻撤下音量斜坡
        if (mode != PlayMode.Highlight) highlightFade.syncFadeVolume()
        // 队列项随模式变化，缓存作废
        cachedMediaItems = null
        // 片段取自持久化表，只查表重建；当前曲目同步生效
        applyHighlightModeChange(appContext, this)
        // 表可能未扫完，后台补齐
        if (mode == PlayMode.Highlight) highlightScan.ensureScan()
    }
    @JvmName("updateSearchMode")
    fun setSearchMode(enabled: Boolean) { isSearchMode = enabled }
    @JvmName("updateSearchResultsVisible")
    fun setSearchResultsVisible(visible: Boolean) { showSearchResults = visible }
    @JvmName("updateSearchQuery")
    fun setSearchQuery(query: String) { searchQuery = query }
    @JvmName("updateSearchSource")
    fun setSearchSource(source: MusicSearchSource) {
        if (searchSource == source) return
        searchSource = source
        // 选择跨进程留存
        searchSourceStore.persist(source)
    }
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
        // 拖动是真实跳变，复位基准并作废歌词时间轴
        progress.seekTo(position)
        lyricTimeline.invalidate()
    }

    // 补扫策略由扫描协调器承担
    internal fun onLibraryChanged() {
        highlightScan.onLibraryChanged()
    }
}

/** 心动模式自行退出的原因，供界面说明 */
enum class HighlightExitReason {
    /** 整队列都定不到副歌，继续跳过只是空转 */
    NoChorus,

    /** USB 输出启用与片段模式相斥，故退出 */
    ImmersiveOutput,
}

/** 两个 USB 输出开关是否有任一启用；按开关判，不等成色 */
internal fun isImmersiveOutput(usbDirectOutputEnabled: Boolean, usbExclusiveEnabled: Boolean): Boolean =
    usbDirectOutputEnabled || usbExclusiveEnabled
