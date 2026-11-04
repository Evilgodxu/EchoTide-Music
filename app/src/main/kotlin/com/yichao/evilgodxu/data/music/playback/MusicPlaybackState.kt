package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.playlist.PlaylistStore
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
    private val metadataEnricher: MetadataEnricher,
    internal val playlistStore: PlaylistStore,
) {

    companion object {
        // 定时关闭时长（分钟）：单次调节步长、下限与上限
        const val TIMER_STEP_MINUTES = 5
        const val TIMER_MIN_MINUTES = 5
        const val TIMER_MAX_MINUTES = 999

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

    // 冷启动恢复任务去重：并发调用方共享同一恢复任务并等待完成，
    // 避免界面、悬浮窗与授权扫描各自触发重复的读盘与解析
    private val restoreMutex = Mutex()
    private var restoreJob: Deferred<Unit>? = null
    var appContext: Context? = null
    var mediaController: MediaController? by mutableStateOf(null)
    var player: Player? by mutableStateOf(null)
    internal var suppressAutoNext = false
    // 播放器事件只在这里分发，迁移逻辑见 PlaybackEventListener
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

    // 进度与坐标换算由 PlaybackProgressTracker 持有；此处按原属性/方法名转发
    val livePositionMs: Long get() = progress.livePositionMs
    val highlightPositionOffsetMs: Long get() = progress.highlightPositionOffsetMs
    val lyricPositionMs: Long get() = progress.lyricPositionMs

    fun toPlayerPosition(absoluteMs: Long): Long = progress.toPlayerPosition(absoluteMs)

    private val persistablePositionMs: Long get() = progress.persistablePositionMs

    private val _playlist = mutableStateOf<List<MusicTrack>>(emptyList())
    // 列表内容版本：后台排序回来时据此判断快照是否仍代表当前列表，过期结果直接丢弃
    internal var playlistRevision = 0
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

    // ===== 心动模式 =====
    // 心动模式的自有状态、扫描与跳过策略由 HighlightModeController 持有

    suspend fun removeUnavailableExternalTracks(context: Context) {
        playlistRemoval.removeUnavailableExternalTracks(context)
    }

    // 缓存下载进行中的曲目 ID 集合：切歌清理时保留这些曲目，等待下载完成后将索引指向本地文件
    val cacheInProgressIds: MutableSet<Long> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    // 自动清理未在播放的纯在线流曲目
    fun cleanupIdleOnlineTracks() {
        playlistRemoval.cleanupIdleOnlineTracks()
    }

    // 删除的是当前曲目时自动递补下一首
    fun removeTrack(trackId: Long, advanceToNext: Boolean = false) {
        playlistRemoval.remove(trackId, advanceToNext)
    }

    fun lyricCacheReferences(excludeTrackId: Long? = null): Set<String>? =
        playlistRemoval.lyricCacheReferences(excludeTrackId)

    suspend fun deleteSongPermanently(context: Context, track: MusicTrack) {
        playlistRemoval.deleteSongPermanently(context, track)
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

    internal val recentPlayedStore = RecentPlayedStore(
        scope = { playbackScope },
        context = { appContext },
    )
    internal val searchHistoryStore = SearchHistoryStore(
        scope = { playbackScope },
        context = { appContext },
    )
    private val playlistPersistence = PlaylistPersistence(
        scope = { playbackScope },
        context = { appContext },
    )
    internal val startupStateLoader = StartupStateLoader(
        searchHistoryStore = searchHistoryStore,
        recentPlayedStore = recentPlayedStore,
        playlistPersistence = playlistPersistence,
    )
    // 冷启动恢复的应用编排由 PlaybackRestorer 承担
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
        playlist = { playlist },
        libraryTracks = { libraryTracks },
        onColorsReady = { uri, colors -> backgroundColors.saveFor(uri, colors) },
        onInvalidatePersistedColors = { backgroundColors.clear() },
    )
    // 封面写入成功后自增，通知封面组件强制重载最新封面
    var coverRevision by artworkCache::coverRevision
    private val highlight = HighlightModeController(
        scope = { playbackScope },
        context = { appContext },
        playMode = { playMode },
        currentTrack = { currentTrack },
        playlist = { playlist },
        currentIndex = { currentIndex },
        libraryTracks = { libraryTracks },
        mediaController = { mediaController },
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
        onClipsUpdated = { applyNewClipsToLoadedQueue(this@MusicPlaybackState) },
        immersiveOutputActive = { immersiveOutputActive },
    )
    // 心动模式自行退出的一次性原因，由界面消费后清空
    var highlightExitNotice by highlight::exitNotice

    internal val progress = PlaybackProgressTracker(
        scope = { playbackScope },
        controller = { mediaController },
        currentTrack = { currentTrack },
        isPrepared = { isPrepared },
        isPlaying = { isPlaying },
        isHighlightClipActive = { highlight.isClipActive() },
        onPlayingChanged = { isPlaying = it },
        onPeriodicPersist = { persistState() },
        onPlaybackCompleted = { recordPlayed(it) },
        onTick = { highlight.syncFadeVolume() },
    )

    // 单曲循环回卷检测、进度单调与周期持久化节流由 PlaybackProgressTracker 持有

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

    // 恢复路径写入面板浏览态：跟随播放队列时忽略来源
    internal fun applyRestoredViewedState(followsQueue: Boolean, source: PlaylistSource?) {
        viewedFollowsQueue = followsQueue
        viewedSource = if (followsQueue) null else source
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

    // 下一首播放插队队列由 PlayNextQueue 持有；此处按原属性/方法名转发
    internal val playNext = PlayNextQueue(currentTrackId = { currentTrack?.id })
    var playNextQueue by playNext::tracks
    // 队列编辑操作由 PlaylistEditor 承担；此处按原方法名转发
    internal val playlistEditor by lazy { PlaylistEditor(this) }
    // 曲目移除与不可用清理由 PlaylistRemovalController 承担；此处按原方法名转发
    internal val playlistRemoval by lazy { PlaylistRemovalController(this) }
    // 待接入队列的起播位置（null = 无待接入队列）：切换歌单时正在播放的曲目仍出声，
    // 新队列暂不装载以免打断播放，待该曲目播完（或用户手动切歌）后从这个位置接入播放器
    internal var pendingQueueStartIndex: Int? = null

    // 曲目是否已在下一首播放队列中
    fun isInPlayNext(trackId: Long): Boolean = playNext.isIn(trackId)

    // 切换下一首播放：已在队列则取消插队，否则加入
    fun togglePlayNext(track: MusicTrack) {
        playNext.toggle(track)
    }

    // 手动切歌时清空插队队列
    fun clearPlayNextQueue() {
        playNext.clear()
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
    internal val sleepTimer = SleepTimerController(
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

    // 全局进度刷新由 PlaybackProgressTracker 承担，此处按原方法名转发
    fun ensurePositionTicker() {
        progress.ensureTicker()
    }

    fun stopPositionTicker() {
        progress.stopTicker()
    }

    // ===== 歌词进度时间轴 =====
    // 快照由 LyricTimeline 持有；此处按原方法名转发，面板侧无需感知拆分
    private val lyricTimeline = LyricTimeline()

    fun seedLyricPosition(trackId: Long?): Long {
        // 取绝对时间：心动模式下控制器回报的是片段内进度，换算回整曲时间后歌词才对得上
        return lyricTimeline.seed(trackId, lyricPositionMs)
    }

    // 面板每轮跟随回写当前进度，供界面重建时接着推进
    fun recordLyricPosition(trackId: Long?, positionMs: Long) {
        lyricTimeline.record(trackId, positionMs)
    }

    internal fun invalidateLyricTimeline() {
        lyricTimeline.invalidate()
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
        // 读取与状态应用由 PlaybackRestorer 承担
        restorer.restore(context)
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
    internal val playbackStateStore = PlaybackStateStore(
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
        // 首帧时播放列表尚未恢复，下标先置 -1，由 PlaybackRestorer 在列表就绪后补齐
        currentIndex = -1
        currentTrack = track.copy(lyricLines = lines)
        currentPosition = snapshot.position
        duration = track.duration
        playMode = PlayMode.entries.getOrElse(snapshot.modeOrdinal) { PlayMode.RepeatAll }
        snapshot.colorUri?.let { backgroundColors.adopt(it, snapshot.colors) }
        pendingSavedUri = track.audioUri
        pendingResumePosition = currentPosition
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
        progress.resetMonotonicBaseline()
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
        // 进度归零：歌词时间轴一并作废，避免下次重建时按释放前的旧进度起步
        progress.reset()
        invalidateLyricTimeline()
        isPlaying = false
        isPrepared = false
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

    // 排序、收藏、重排、元数据替换与 ID 迁移由 PlaylistEditor 承担；此处按原方法名转发
    fun sortPlaylistForDefaultOrder(tracks: List<MusicTrack>): List<MusicTrack> =
        playlistEditor.sortForDefaultOrder(tracks)

    fun sortByActiveRule(tracks: List<MusicTrack>): List<MusicTrack> =
        playlistEditor.sortByActiveRule(tracks)

    fun applySortedPlaylist(sorted: List<MusicTrack>) {
        playlistEditor.applySorted(sorted)
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

    fun updateTrack(updated: MusicTrack) {
        playlistEditor.updateTrack(updated)
    }

    suspend fun migrateTrackId(context: Context, oldId: Long, newId: Long) {
        playlistEditor.migrateTrackId(context, oldId, newId)
    }

    suspend fun bumpCoverRevision() {
        artworkCache.bumpCoverRevision()
    }

    fun batchUpdateTracks(updates: List<MusicTrack>, persist: Boolean = true) {
        playlistEditor.batchUpdate(updates, persist)
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
        progress.syncFromController(isActive)
    }

    fun updatePosition() {
        progress.updatePosition()
    }

    internal fun calculateIndex(direction: Int, repeatOne: Boolean, from: Int = currentIndex): Int {
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

    internal fun autoNextIndex(): Int = calculateIndex(direction = 1, repeatOne = true)

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
            highlight.notifyImmersiveBlocked()
            return
        }
        val previous = playMode
        playMode = mode
        highlight.onModeChanged()
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
        if (mode == PlayMode.Highlight) highlight.ensureScan(context)
    }

    fun onImmersiveOutputChanged() {
        highlight.onImmersiveOutputChanged()
    }

    // 后台扫描全库副歌片段由 HighlightModeController 承担，详见其实现说明
    fun ensureHighlightScan(context: Context) {
        highlight.ensureScan(context)
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
        // 拖动是真实跳变：复位单调基准并改写位置，歌词时间轴一并作废
        progress.seekTo(position)
        invalidateLyricTimeline()
    }

    // 本地曲库变化后的补扫策略由 HighlightModeController 承担
    internal fun onLibraryChanged() {
        highlight.onLibraryChanged()
    }

    /**
     * 心动模式下曲目落定后的收尾由 HighlightModeController 承担。
     */
    internal fun settleHighlightOnTrackChange() {
        highlight.onTrackSettled()
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
