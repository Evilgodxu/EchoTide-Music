package com.yichao.evilgodxu.data.music.playback

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.metadata.EmbeddedCoverCache
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.music.trackIdentityKey
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import java.io.File
import kotlin.jvm.JvmName
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// 音乐播放器状态持有者（悬浮窗级共享状态）
class MusicPlaybackState(
    private val metadataEnricher: MetadataEnricher,
    private val playlistStore: PlaylistStore,
) {

    // 常听收录窗口：统计 3 天内完整播放次数不少于 2 次的歌曲
    companion object {
        // 播放速度调节范围与默认值：步长 0.1
        const val PLAYBACK_SPEED_MIN = 0.5f
        const val PLAYBACK_SPEED_MAX = 2.0f
        const val PLAYBACK_SPEED_DEFAULT = 1.0f
        private const val RECENT_WINDOW_DAYS = 3
        private const val RECENT_MIN_PLAYS = 2
        // 播放期间周期性持久化间隔：保证冷启动/异常退出也能恢复当前曲目与进度
        private const val STATE_PERSIST_INTERVAL_MS = 3000L
        // 单曲循环回卷判定：位置回退超过该值且曾越过曲目中部，视为一次完整播放
        private const val LOOP_RESTART_MIN_JUMP_MS = 3000L
        // 回卷检测与过渡回调记录的去重冷却：同一次循环只计入一次完整播放
        private const val AUTO_COUNT_COOLDOWN_MS = 2000L
        // 进度单调复位兜底：未触发切歌/拖动回调但位置大幅回退（如切换歌单重载同 ID 曲目）时视为重置；
        // 小幅回退仍按流媒体回锚处理，保持进度单调
        private const val MONO_REBASELINE_JUMP_MS = 3000L
    }

    // 上次持久化播放状态的时刻，用于播放期间节流写入
    private var lastStatePersistAt = 0L

    private val savedUriKey = stringPreferencesKey("music_saved_uri")
    private val savedPositionKey = longPreferencesKey("music_saved_position")
    private val savedModeKey = intPreferencesKey("music_saved_mode")
    private val savedSpeedKey = floatPreferencesKey("music_saved_speed")
    // 首页背景渐变取色结果持久化键：与播放快照同库写入，冷启动恢复后首帧即可渲染
    private val savedGradientUriKey = stringPreferencesKey("music_saved_gradient_uri")
    private val savedGradientTopKey = intPreferencesKey("music_saved_gradient_top")
    private val savedGradientBottomKey = intPreferencesKey("music_saved_gradient_bottom")
    private val playlistCacheKey = "music_playlist_cache"
    private val playlistCachePreferences = "music_playlist_cache_preferences"
    // 当前歌单来源与默认库备份持久化键，重启后恢复选中状态
    private val playlistSourceKeyPref = "music_playlist_source_key"
    private val playlistSourceNamePref = "music_playlist_source_name"
    private val defaultPlaylistCacheKeyPref = "music_default_playlist_cache"
    // 排序规则持久化键
    private val playlistSortFieldPref = "music_playlist_sort_field"
    private val playlistSortDescPref = "music_playlist_sort_descending"
    private val searchHistoryKey = "music_search_history"
    private val searchHistoryPreferences = "music_search_history_preferences"
    // 待落盘的播放状态快照：每次调用覆盖为最新值，写入任务按需消费，合并连续写入
    private var pendingStateSnapshot: SavedPlaybackState? = null
    // 播放状态写入任务：在途时新调用只更新快照，由在途循环以最新快照收尾，
    // 避免取消旧任务产生"旧任务已取消、新任务未启动"的写入间隙
    private var stateWriteJob: Job? = null
    private var playlistPersistJob: Job? = null
    private val persistenceMutex = Mutex()
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
            // 播放已离开旧文件：作废升级后待删的旧本地文件（当前项仍引用则保留，等下次过渡）
            flushPendingFileDeletes(mediaItem?.localConfiguration?.uri?.toString())
            // 曲目自然播完即计一次完整播放，作为常听收录依据：
            // AUTO=自动续播/单曲结束切下一首；REPEAT=单曲循环重播当前曲目。
            // 手动切歌(SEEK)、列表变更(PLAYLIST_CHANGED)非自然结束，不计入。
            // 需在更新 currentTrack 前记录，此处 currentTrack 仍为刚播完的上一首。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                currentTrack?.id?.let { recordPlayed(it) }
            }
            if (stopAfterCurrentTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 定时关闭：当前曲目自然结束 → 停止播放
                completeSleepTimer()
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
                            playTrackAt(appContext ?: return@launch, this@MusicPlaybackState, queuedIndex, clearQueue = false)
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
                            playTrackAt(appContext ?: return@launch, this@MusicPlaybackState, next, clearQueue = false)
                        }
                        return
                    }
                }
            }
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            val index = playlist.indexOfFirst { it.id == id }
            if (index >= 0) {
                // 续播锚点：playTrackAt 以保存位置起播时已记录目标位置，本回调（异步派发）据此保留
                // 已还原的进度，避免把进度清空到 0 再回填；真实切歌（无锚点）才复位到起点
                val resumePos = resumeAnchorPosition
                resumeAnchorPosition = -1L
                currentIndex = index
                currentTrack = playlist[index]
                isPrepared = false
                currentPosition = if (resumePos > 0L) resumePos else 0L
                duration = if (resumePos > 0L) playlist[index].duration else 0L
                // 切歌或单曲循环重播：复位进度单调基准，允许进度回到起点
                lastMonoMediaId = null
                // 切换曲目即持久化最新 URI，确保后台自动下一首也能被冷启动恢复
                persistState()
                // 切歌后主动预读新曲源格式，避免信息条等待解码回填而长时间空白
                appContext?.let { refreshIdleTrackFormatInfo(it) }
            }
            // 切换曲目后清理未在播放的在线歌曲，避免在线播放曲目在播放列表中常驻；
            // 元数据补全只在缓存完成时执行一次，切歌不再触发，避免重复写封面并触发系统级文件扫描
            cleanupIdleOnlineTracks()
            // 再次从控制器校正当前曲目，确保 UI 与真实音频一致（在线曲目切换时尤其关键）
            syncPlaybackState()
            // 切歌后以权威曲目状态刷新系统媒体面板的当前 MediaItem（封面/标题），
            // 覆盖缓存 MediaItem 中封面补全前的空白或旧封面，避免面板封面与当前歌曲不匹配
            refreshCurrentMediaItem(this@MusicPlaybackState)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
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
                    // 常规自然播完/单曲循环已在 onMediaItemTransition 中记录，此处不会重复
                    currentTrack?.id?.let { recordPlayed(it) }
                    if (suppressAutoNext) {
                        suppressAutoNext = false
                        return
                    }
                    if (stopAfterCurrentTrack) {
                        // 定时关闭：曲目播毕停止播放
                        completeSleepTimer()
                        return
                    }
                    val next = autoNextIndex()
                    if (next >= 0) {
                        playbackScope.launch {
                            playTrackAt(appContext ?: return@launch, this@MusicPlaybackState, next, clearQueue = false)
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
    var currentPosition by mutableLongStateOf(0L)
    private val _playlist = mutableStateOf<List<MusicTrack>>(emptyList())
    var playlist: List<MusicTrack>
        get() = _playlist.value
        set(value) {
            _playlist.value = value
            cachedMediaItems = null
            mediaItemsDirty = true
        }
    /** 缓存 playlist 对应的 MediaItem 列表，避免切歌时重复构建 */
    var cachedMediaItems by mutableStateOf<List<androidx.media3.common.MediaItem>?>(null)
    /** 封面更新后需要刷新系统媒体面板的 MediaItem，标记为脏 */
    var mediaItemsDirty by mutableStateOf(false)
    var currentIndex by mutableIntStateOf(-1)
    var currentTrack by mutableStateOf<MusicTrack?>(null)
    var playMode by mutableStateOf(PlayMode.RepeatAll)
    // 播放速度：默认 1.0，调节范围 0.5~2.0
    var playbackSpeed by mutableFloatStateOf(PLAYBACK_SPEED_DEFAULT)
    var errorMsg by mutableStateOf<String?>(null)
    var isScanning by mutableStateOf(false)
    // 元数据补全（封面解码 / 歌词读取）进行中：曲库分析的自动触发据此让路，
    // 避免频谱解码与封面解码在同一时间窗内争抢 CPU 与原生解码器内存
    var isEnrichingMetadata by mutableStateOf(false)
    var isLyricsVisible by mutableStateOf(false)

    // 在线搜索相关状态
    var isSearchMode by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    // 当前选中的在线搜索平台，单平台搜索时使用
    var searchSource by mutableStateOf(MusicSearchSource.NETEASE)
    var searchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var searchHistory by mutableStateOf<List<String>>(emptyList())
    var isSearching by mutableStateOf(false)
    // 当前搜索协程句柄：新搜索发起时取消上一次，避免过期响应覆盖新查询结果
    var searchJob: Job? = null
    // 搜索结果分页：已加载页数、是否正在加载更多、是否还有更多
    var searchPage by mutableIntStateOf(0)
    var isLoadingMore by mutableStateOf(false)
    var hasMoreSearchResults by mutableStateOf(true)
    // 代理音源一次拉取的全量结果缓冲：本地按页切分展示，避免不支持分页的代理重复请求
    var searchPending by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    // 代理音源首次请求是否拉满（可能支持分页，缓冲耗尽后继续请求下一页）
    var searchPendingFull by mutableStateOf(false)
    // 加载更多分页的协程句柄：新搜索发起时取消，避免过期分页混入新结果
    var searchLoadJob: Job? = null
    var showSearchResults by mutableStateOf(false)
    var pendingSearchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var closeSearchResultsOnReady by mutableStateOf(false)
    // 首页音质选择对话框：非空时显示，目标为待播在线歌曲
    var qualityPickTrack by mutableStateOf<NeteaseSongSearchResult?>(null)
    // 音质尝试中：解析地址与等待播放结果期间置 true，阻止重复点击/误关对话框
    var qualityBusy by mutableStateOf(false)
    // 最近一次音质尝试失败提示（失败时保留对话框展示，供用户换其它音质）
    var qualityError by mutableStateOf<String?>(null)
    // 音质试播曲目 ID：播放就绪(READY)后清空；播放失败时据此移除试播曲目并保留对话框
    var pendingQualityPlayTrackId by mutableStateOf<Long?>(null)
    // 无损升级进行中：阻止重复触发与误关对话框
    var losslessUpgradeBusy by mutableStateOf(false)
    // 最近一次无损升级失败提示：升级失败时保留对话框展示，供用户重试
    var losslessUpgradeError by mutableStateOf<String?>(null)
    // 无损升级候选：按来源搜索的在线原曲，用户确认选中后下载无损替换本地文件
    var losslessUpgradeCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isLosslessUpgradeSearching by mutableStateOf(false)
    // 无损升级候选搜索来源
    var losslessUpgradeSource by mutableStateOf(MusicSearchSource.NETEASE)
    var coverCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isCoverSearching by mutableStateOf(false)
    var localCoverCandidates by mutableStateOf<List<RecentCover>>(emptyList())
    // 封面写入成功后自增：封面写进音频文件后其 URI 不变而系统略缩图已变，
    // 通知封面组件重新取系统略缩图
    var coverRevision by mutableIntStateOf(0)
    var lyricsCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isLyricsSearching by mutableStateOf(false)
    var isLyricsRefreshing by mutableStateOf(false)
    var lyricsRefreshError by mutableStateOf<String?>(null)
    // 歌词/封面刷新当前来源：按来源独立搜索，切换来源时轮换并重新搜索
    var lyricsRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)
    var coverRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)

    private fun hasUriAccess(context: Context, audioUri: String): Boolean {
        val uri = Uri.parse(audioUri)
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
                            val scheme = Uri.parse(track.audioUri).scheme
                            scheme != null && scheme !in listOf("http", "https")
                        }.getOrElse { false } &&
                        runCatching { Uri.parse(track.audioUri).scheme == ContentResolver.SCHEME_CONTENT }.getOrElse { false } &&
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
                clearSavedState(context)
            }
            persistPlaylist()
        }
    }

    private suspend fun clearSavedState(context: Context) {
        withContext(Dispatchers.IO) {
            context.settingsDataStore.edit { preferences ->
                preferences.remove(savedUriKey)
                preferences.remove(savedPositionKey)
            }
        }
    }

    // 仅清除持久化的播放位置（定时关闭时使用，保留歌曲 URI）
    private suspend fun clearSavedPosition(context: Context) {
        withContext(Dispatchers.IO) {
            context.settingsDataStore.edit { preferences ->
                preferences.remove(savedPositionKey)
            }
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
        val scheme = runCatching { Uri.parse(track.audioUri).scheme }.getOrNull()
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
                currentIndex = nextIndex
                currentTrack = playlist[nextIndex]
                isPlaying = false
                isPrepared = false
                currentPosition = 0L
                duration = 0L
                errorMsg = null
                appContext?.let { context ->
                    playbackScope.launch {
                        playTrackAt(context, this@MusicPlaybackState, nextIndex, autoPlay = wasPlaying, clearQueue = false)
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

    // 彻底删除歌曲：移除音频源文件与仅该曲引用的歌词缓存，并同步库、播放队列与歌单引用
    suspend fun deleteSongPermanently(context: Context, track: MusicTrack) {
        // 删除前基于全量库+当前列表计算剩余曲目的歌词缓存引用，作为清除依据：
        // 全量库覆盖本地曲目，当前列表兜底在线曲目（在线曲目只存在于当前列表，不在全量库备份）
        val remaining = (defaultPlaylistBackup.orEmpty() + playlist)
            .filterNot { it.id == track.id }
            .distinctBy { it.id }
        withContext(Dispatchers.IO) {
            deleteAudioSource(context, track)
            // 停留在自定义歌单且全量库备份缺失时，当前列表仅含歌单子集，引用集不全，
            // 跳过清理，交由后续 enrichAndCleanup 以全量库+歌单的并集统一回收，避免误删其他歌曲共享缓存
            val libraryKnown = playlistSource == null || defaultPlaylistBackup != null
            if (libraryKnown) {
                MusicMetadataCache.cleanupOrphanedMetadata(
                    context,
                    remaining.map { it.lyricCachePath }.toSet(),
                )
            }
        }
        defaultPlaylistBackup = defaultPlaylistBackup?.filterNot { it.id == track.id }
        removeTrack(track.id, advanceToNext = true)
        likedIds = likedIds - track.id
        removeFromRecentPlayed(track.id)
        // 读盘切到 IO：首次 getSharedPreferences 需同步解析整份歌单 JSON，不应占用主线程
        playlistStore.awaitLoaded(context)
        playlistStore.removeTrackFromAll(context, track.id)
    }

    // 删除音频源文件：先经 MediaStore 删除（同时清理媒体条目），失败则直接删本地路径并通知媒体库同步
    private fun deleteAudioSource(context: Context, track: MusicTrack) {
        deleteAudioSourceByRef(context, track.audioUri, track.path)
    }

    // 无损升级后待删的旧本地文件：旧文件仍被当前播放项占用时先登记，待播放离开后再删，
    // 避免升级过程中断当前播放（部分设备上删除正在播放的文件会引发播放失败）
    private data class PendingFileDelete(val audioUri: String, val path: String?)
    private val pendingFileDeletes = mutableListOf<PendingFileDelete>()

    // 登记待删的旧本地文件（无损升级替换音频源后用）；真正的删除推迟到播放离开该文件时
    fun queueOldFileDelete(audioUri: String, path: String?) {
        if (audioUri.isBlank()) return
        pendingFileDeletes += PendingFileDelete(audioUri, path)
    }

    // 播放离开某曲目后作废对应旧文件：当前播放项已不再引用则删除，仍占用则保留待下次过渡
    private fun flushPendingFileDeletes(playingUri: String?) {
        if (pendingFileDeletes.isEmpty()) return
        val context = appContext ?: return
        val toDelete = pendingFileDeletes.filter { it.audioUri != playingUri }
        if (toDelete.isEmpty()) return
        pendingFileDeletes.removeAll(toDelete)
        playbackScope.launch {
            withContext(Dispatchers.IO) {
                toDelete.forEach { deleteAudioSourceByRef(context, it.audioUri, it.path) }
            }
        }
    }

    // 按 URI 与本地路径删除音频源文件：先经 MediaStore 删除（同时清理媒体条目），
    // 失败则直接删本地路径并通知媒体库同步
    private fun deleteAudioSourceByRef(context: Context, audioUri: String, path: String?) {
        val uri = audioUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
        // 纯在线流曲目无本地文件，无需文件级删除
        if (uri?.scheme == "http" || uri?.scheme == "https") return
        val deletedViaResolver = uri != null &&
            runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0
        if (!deletedViaResolver && path?.isNotBlank() == true) {
            if (runCatching { File(path).delete() }.getOrDefault(false)) {
                // 直删文件后触发媒体扫描，使 MediaStore 中该文件的条目失效，避免歌曲重新出现
                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            }
        }
    }

    var audioSignalPathFormat by mutableStateOf<AudioSignalPathFormat?>(null)
    // 音频信息所属曲目：保证格式信息始终与当前曲目对应，后台切歌后再回前台不会错配
    var audioSignalPathTrackId by mutableStateOf<Long?>(null)

    // 收藏的歌曲 ID 集合（面板级内存状态）
    var likedIds by mutableStateOf<Set<Long>>(emptySet())

    // 常听：3 天内完整播放次数不少于 2 次的歌曲，按最近一次播放时间倒序
    private var recentPlayEvents by mutableStateOf<List<PlayEvent>>(emptyList())
    private val recentPlayedPreferences = "music_recent_played_preferences"
    private val recentPlayedKey = "music_recent_played_events"
    private val recentWindowMs: Long
        get() = RECENT_WINDOW_DAYS * 24L * 60 * 60 * 1000

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

    val recentPlayedIds: List<Long>
        get() {
            val cutoff = System.currentTimeMillis() - recentWindowMs
            val window = recentPlayEvents.filter { it.timestamp >= cutoff }
            return window.groupBy { it.trackId }
                .filterValues { it.size >= RECENT_MIN_PLAYS }
                .entries
                .sortedByDescending { it.value.maxOf { e -> e.timestamp } }
                .map { it.key }
        }

    // 当前播放列表来源歌单（null = 默认全量播放列表）
    var playlistSource by mutableStateOf<PlaylistSource?>(null)
    // 播放列表排序规则（仅对默认全量播放列表生效），随列表一并持久化
    var playlistSortField by mutableStateOf(PlaylistSortField.DEFAULT)
    var playlistSortDescending by mutableStateOf(false)
    // 默认全量播放列表备份：首次切到歌单时快照，供快捷切回默认
    var defaultPlaylistBackup by mutableStateOf<List<MusicTrack>?>(null)
    // 全量库：优先备份，否则为当前播放列表
    val libraryTracks: List<MusicTrack>
        get() = defaultPlaylistBackup ?: playlist

    // 记录一次完整播放：追加带时间戳的播放记录，并清理超出 3 天窗口的旧记录
    fun recordPlayed(trackId: Long) {
        val now = System.currentTimeMillis()
        recentPlayEvents = listOf(PlayEvent(trackId, now)) +
            recentPlayEvents.filter { it.timestamp >= now - recentWindowMs }
        persistRecentPlayed()
    }

    // 从常听手动移除：清除该曲目的播放记录，期间不再自动收录
    fun removeFromRecentPlayed(trackId: Long) {
        recentPlayEvents = recentPlayEvents.filterNot { it.trackId == trackId }
        persistRecentPlayed()
    }

    private fun persistRecentPlayed() {
        val context = appContext ?: return
        playbackScope.launch(Dispatchers.IO) {
            context.getSharedPreferences(recentPlayedPreferences, Context.MODE_PRIVATE)
                .edit()
                .putString(
                    recentPlayedKey,
                    recentPlayEvents.joinToString(",") { "${it.trackId}:${it.timestamp}" },
                ).commit()
        }
    }

    // 下一首播放插队队列：自然播完后依次播放队列曲目，再接续原播放位置
    var playNextQueue by mutableStateOf<List<MusicTrack>>(emptyList())
    // 建立队列时记录的当前曲目 ID，队列播完后据此接续原播放位置
    private var queueResumeTrackId: Long? by mutableStateOf(null)

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

    // 定时关闭相关状态（后台计时）
    var timerMinutes by mutableIntStateOf(10)
    var timerRemaining by mutableIntStateOf(0)
    var timerAutoStopped by mutableStateOf(false)
    // 定时关闭到点后请求真正退出应用（一次性信号，退出编排由应用外壳执行）
    var sleepTimerExpired by mutableStateOf(false)
    private val timerJob = SupervisorJob()
    private val timerScope = CoroutineScope(timerJob + Dispatchers.Main)
    private var countdownJob: Job? = null
    private var stopAfterCurrentTrack = false

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
                if (isPlaying) updatePosition()
                delay(200)
            }
        }
    }

    fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
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
        searchHistory = withContext(Dispatchers.IO) {
            context.getSharedPreferences(searchHistoryPreferences, Context.MODE_PRIVATE)
                .getString(searchHistoryKey, "")
                ?.split("\n")
                ?.filter(String::isNotBlank)
                .orEmpty()
        }
        recentPlayEvents = withContext(Dispatchers.IO) {
            context.getSharedPreferences(recentPlayedPreferences, Context.MODE_PRIVATE)
                .getString(recentPlayedKey, "")
                ?.split(",")
                ?.mapNotNull { token ->
                    val idx = token.lastIndexOf(':')
                    if (idx <= 0) return@mapNotNull null
                    val id = token.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                    val ts = token.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
                    PlayEvent(id, ts)
                }
                .orEmpty()
        }
        val preferences = withContext(Dispatchers.IO) {
            context.settingsDataStore.data.first()
        }
        val cachedPlaylist = withContext(Dispatchers.IO) {
            // 历史缓存可能残留同文件不同 URI 形态的重复条目，按真实文件路径去重，避免冷启动直接展示重名歌曲
            loadCachedPlaylist(context, playlistCacheKey).distinctBy { trackIdentityKey(context, it) }
        }
        // 恢复上次选中的歌单来源、排序规则与默认库备份，扫描刷新后保持选中与排序
        val (savedSource, savedSortField, savedSortDescending) = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(playlistCachePreferences, Context.MODE_PRIVATE)
            val source = prefs.getString(playlistSourceKeyPref, null)?.let { key ->
                PlaylistSource(key, prefs.getString(playlistSourceNamePref, "") ?: "")
            }
            val field = prefs.getString(playlistSortFieldPref, null)
                ?.let { name -> runCatching { PlaylistSortField.valueOf(name) }.getOrNull() }
                ?: PlaylistSortField.DEFAULT
            Triple(source, field, prefs.getBoolean(playlistSortDescPref, false))
        }
        // 默认全量播放列表套用保存的排序规则；大库排序开销明显，放 IO 执行
        val orderedCachedPlaylist = withContext(Dispatchers.IO) {
            if (savedSource == null) {
                sortTracks(cachedPlaylist, savedSortField, savedSortDescending)
            } else {
                cachedPlaylist
            }
        }
        val cachedBackup = withContext(Dispatchers.IO) {
            loadCachedPlaylist(context, defaultPlaylistCacheKeyPref).distinctBy { trackIdentityKey(context, it) }
        }
        val savedUri = preferences[savedUriKey]
        val savedPosition = preferences[savedPositionKey] ?: 0L
        val savedMode = preferences[savedModeKey] ?: PlayMode.RepeatAll.ordinal
        val savedSpeed = preferences[savedSpeedKey] ?: PLAYBACK_SPEED_DEFAULT
        val restoredGradientUri = preferences[savedGradientUriKey]
        val restoredGradientTop = preferences[savedGradientTopKey]
        val restoredGradientBottom = preferences[savedGradientBottomKey]
        withContext(Dispatchers.Main) {
            // 无保存来源时处于全量播放列表
            playlistSource = savedSource
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
            pendingSavedUri = savedUri
            pendingResumePosition = savedPosition
            savedGradient = if (restoredGradientTop != null && restoredGradientBottom != null) {
                Color(restoredGradientTop) to Color(restoredGradientBottom)
            } else null
            savedGradientUri = restoredGradientUri
            if (currentTrack == null) {
                currentPosition = savedPosition
            }
            playMode = PlayMode.entries.getOrElse(savedMode) { PlayMode.RepeatAll }
            playbackSpeed = savedSpeed.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)
        }
    }

    // 冷启动未播放时预读当前曲目格式信息，供音频信息条展示；开始播放后由解码头覆盖
    fun refreshIdleTrackFormatInfo(context: Context) {
        val track = currentTrack ?: return
        if (audioSignalPathTrackId == track.id) return
        playbackScope.launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            if (currentTrack?.id == track.id) {
                audioSignalPathFormat = info
                audioSignalPathTrackId = track.id
            }
        }
    }

    // 缓存完成后以本地缓存文件为源强制补齐当前曲目格式信息，避免在线播放期间信息条空白
    fun refreshTrackFormatInfoFromLocal(context: Context) {
        val track = currentTrack ?: return
        if (!track.isLocalAudioSource) return
        playbackScope.launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            if (currentTrack?.id == track.id) {
                audioSignalPathFormat = info
                audioSignalPathTrackId = track.id
            }
        }
    }

    // 回到前台时校正音频信息：与当前曲目错配时清掉旧值并重新读取当前曲目源格式，
    // 保证信息条始终对应当前曲目而不依赖解码回调回填
    fun reconcileTrackFormatInfo(context: Context) {
        val track = currentTrack ?: return
        if (audioSignalPathTrackId == track.id) return
        audioSignalPathFormat = null
        audioSignalPathTrackId = null
        refreshIdleTrackFormatInfo(context)
    }

    // 持久化播放速度，供重启后恢复
    private fun persistPlaybackSpeed() {
        val context = appContext ?: return
        playbackScope.launch(Dispatchers.IO) {
            context.settingsDataStore.edit { preferences ->
                preferences[savedSpeedKey] = playbackSpeed
            }
        }
    }

    fun persistPlaylist() {
        val context = appContext ?: return
        // 合并连续写入：取消未开始的上一次任务，仅保留最后一次持久化
        playlistPersistJob?.cancel()
        playlistPersistJob = playbackScope.launch {
            withContext(Dispatchers.IO) {
                // 单次 Editor 一次落盘：当前列表、歌单来源、默认库备份同属一份 XML，
                // 拆成三次 commit 会把整份文件重写三遍，且中途进程被杀会留下
                // 「列表已更新、来源或备份未更新」的不一致状态
                val source = playlistSource
                val backup = defaultPlaylistBackup
                val editor = context.getSharedPreferences(playlistCachePreferences, Context.MODE_PRIVATE).edit()
                editor.putString(playlistCacheKey, encodePlaylist(playlist))
                editor.putString(playlistSourceKeyPref, source?.key)
                editor.putString(playlistSourceNamePref, source?.name)
                editor.putString(playlistSortFieldPref, playlistSortField.name)
                editor.putBoolean(playlistSortDescPref, playlistSortDescending)
                if (backup != null) {
                    editor.putString(defaultPlaylistCacheKeyPref, encodePlaylist(backup))
                } else {
                    editor.remove(defaultPlaylistCacheKeyPref)
                }
                // 同步写盘：播放列表缓存为用户关键数据，apply 异步落盘存在进程被杀丢失窗口
                editor.commit()
            }
        }
    }

    fun addSearchHistory(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        searchHistory = listOf(normalized) + searchHistory.filterNot { it == normalized }
        searchHistory = searchHistory.take(10)
        persistSearchHistory()
    }

    fun removeSearchHistory(query: String) {
        searchHistory = searchHistory.filterNot { it == query }
        persistSearchHistory()
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
        persistSearchHistory()
    }

    private fun persistSearchHistory() {
        val context = appContext ?: return
        playbackScope.launch(Dispatchers.IO) {
            context.getSharedPreferences(searchHistoryPreferences, Context.MODE_PRIVATE)
                .edit()
                .putString(searchHistoryKey, searchHistory.joinToString("\n"))
                .commit()
        }
    }

    private fun loadCachedPlaylist(context: Context, cacheKey: String): List<MusicTrack> {
        val json = context.getSharedPreferences(playlistCachePreferences, Context.MODE_PRIVATE)
            .getString(cacheKey, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val savedLyricPath = item.optString("lyricCachePath", "")
                val lyricOffset = item.optLong("lyricOffsetMs", 0L)
                // 歌词内容延迟到显示时按需从缓存文件读取（含偏移），
                // 冷启动不逐首解析歌词，避免大歌单的数百次文件读取拖慢所有界面首帧
                val lyricCachePath = savedLyricPath.takeIf { MusicMetadataCache.isValid(it) }.orEmpty()
                MusicTrack(
                    id = item.getLong("id"),
                    path = item.getString("path"),
                    audioUri = item.getString("audioUri"),
                    title = item.getString("title"),
                    artist = item.getString("artist"),
                    duration = item.getLong("duration"),
                    albumId = item.getLong("albumId"),
                    albumName = item.optString("albumName", ""),
                    neteaseId = item.optLong("neteaseId", 0L),
                    neteaseCoverUrl = item.optString("neteaseCoverUrl", ""),
                    isFavorite = item.optBoolean("isFavorite", false),
                    isOnlinePlay = item.optBoolean("isOnlinePlay", false),
                    lyricCachePath = lyricCachePath,
                    lyricLines = emptyList(),
                    lyricOffsetMs = lyricOffset,
                    lyricFailed = item.optBoolean("lyricFailed", false),
                    fileModifiedMs = item.optLong("fileModifiedMs", 0L),
                )
            }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPlaybackState", "读取缓存的播放列表失败", e)
            emptyList()
        }
    }

    // 曲目列表序列化为 JSON 文本，交由调用方与其它键合并到同一次落盘
    private fun encodePlaylist(tracks: List<MusicTrack>): String {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id)
                put("path", track.path)
                put("audioUri", track.audioUri)
                put("title", track.title)
                put("artist", track.artist)
                put("duration", track.duration)
                put("albumId", track.albumId)
                put("albumName", track.albumName)
                put("neteaseId", track.neteaseId)
                put("neteaseCoverUrl", track.neteaseCoverUrl)
                put("lyricCachePath", track.lyricCachePath)
                put("isOnlinePlay", track.isOnlinePlay)
                put("isFavorite", track.isFavorite)
                put("lyricOffsetMs", track.lyricOffsetMs)
                put("lyricFailed", track.lyricFailed)
                put("fileModifiedMs", track.fileModifiedMs)
            })
        }
        return array.toString()
    }

    var pendingSavedUri: String? = null
    var pendingResumePosition: Long = 0L
    // 已持久化的首页背景取色结果及其所属曲目 URI：冷启动首帧、略缩图就绪前供背景直接使用
    var savedGradient: Pair<Color, Color>? by mutableStateOf(null)
        private set
    var savedGradientUri: String? by mutableStateOf(null)
        private set

    // 仅当曲目与取色结果同源时返回，避免运行时切歌后旧曲目的恢复色闪帧
    fun restoredGradientFor(track: MusicTrack?): Pair<Color, Color>? =
        if (track != null && track.audioUri == savedGradientUri) savedGradient else null

    // 无损升级替换音频文件时 URI 变化但封面/背景不变：把已持久化的取色结果改指到新 URI，
    // 避免升级后背景回落默认色（新文件系统略缩图未就绪前也保持既有背景）
    fun remapGradientUri(fromUri: String, toUri: String) {
        if (savedGradientUri != fromUri) return
        savedGradientUri = toUri
        val gradient = savedGradient ?: return
        val context = appContext ?: return
        playbackScope.launch {
            withContext(Dispatchers.IO) {
                context.settingsDataStore.edit { preferences ->
                    preferences[savedGradientUriKey] = toUri
                    preferences[savedGradientTopKey] = gradient.first.toArgb()
                    preferences[savedGradientBottomKey] = gradient.second.toArgb()
                }
            }
        }
    }

    // 首页背景真实取色成功后持久化，供下次冷启动恢复
    fun saveBackgroundGradient(top: Color, bottom: Color) {
        val uri = currentTrack?.audioUri ?: return
        savedGradient = top to bottom
        savedGradientUri = uri
        val context = appContext ?: return
        playbackScope.launch {
            withContext(Dispatchers.IO) {
                context.settingsDataStore.edit { preferences ->
                    preferences[savedGradientUriKey] = uri
                    preferences[savedGradientTopKey] = top.toArgb()
                    preferences[savedGradientBottomKey] = bottom.toArgb()
                }
            }
        }
    }

    // 续播锚点：playTrackAt 以保存位置起播时记录该目标，供异步派发的 onMediaItemTransition 保留已还原进度。
    // -1 表示无续播锚点（真实切歌/重播），过渡回调按常规复位进度到起点。
    internal var resumeAnchorPosition: Long = -1L

    fun persistState() {
        val context = appContext ?: return
        val track = currentTrack ?: return
        // 调用时刻立即快照：release/softRelease 随后会清空播放状态，异步写入不能再回读内存态
        pendingStateSnapshot = SavedPlaybackState(track.audioUri, currentPosition, playMode.ordinal)
        // 写入在途时仅更新快照，由在途任务以最新快照收尾，不再取消旧任务
        if (stateWriteJob?.isActive == true) return
        stateWriteJob = playbackScope.launch {
            persistenceMutex.withLock {
                while (true) {
                    val snapshot = pendingStateSnapshot ?: break
                    pendingStateSnapshot = null
                    withContext(Dispatchers.IO) {
                        context.settingsDataStore.edit { preferences ->
                            preferences[savedUriKey] = snapshot.audioUri
                            preferences[savedPositionKey] = snapshot.position
                            preferences[savedModeKey] = snapshot.mode
                        }
                    }
                }
            }
        }
    }

    fun softRelease() {
        stopPositionTicker()
        persistState()
        currentTrack?.let { track ->
            pendingSavedUri = track.audioUri
            pendingResumePosition = currentPosition
        }
        // 清掉未消费的续播锚点，避免释放后残留锚点被后续非续播的过渡回调误用
        resumeAnchorPosition = -1L
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
            pendingResumePosition = currentPosition
        }
        // 清掉未消费的续播锚点，避免释放后残留锚点被后续非续播的过渡回调误用
        resumeAnchorPosition = -1L
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
        isPlaying = false
        isPrepared = false
        duration = 0L
        // 释放播放器后复位单调基准，避免恢复播放时进度被残留基准钳到高位
        lastMonoMediaId = null
        errorMsg = null
        stopTimer()
    }

    // 定时关闭到点收尾：停止播放并发出退出应用信号（退出编排由应用外壳执行）
    private fun completeSleepTimer() {
        stopAfterCurrentTrack = false
        timerAutoStopped = true
        sleepTimerExpired = true
        release()
        playbackScope.launch {
            appContext?.let { clearSavedPosition(it) }
        }
    }

    // 启动定时关闭（分钟），计时结束后停止播放并释放资源
    fun startTimer(minutes: Int) {
        stopTimer()
        timerMinutes = minutes
        timerRemaining = minutes
        countdownJob = timerScope.launch {
            while (timerRemaining > 0) {
                delay(60_000L)
                timerRemaining--
            }
            // 计时结束：当前歌曲播放完成后停止并释放资源
            if (isPlaying) {
                stopAfterCurrentTrack = true
                withContext(Dispatchers.Main) {
                    mediaController?.let { controller ->
                        controller.repeatMode = Player.REPEAT_MODE_OFF
                        controller.shuffleModeEnabled = false
                    }
                }
            } else {
                completeSleepTimer()
            }
        }
    }

    // 取消定时关闭
    fun stopTimer() {
        stopAfterCurrentTrack = false
        countdownJob?.cancel()
        countdownJob = null
        timerRemaining = 0
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
            playbackScope.launch {
                val ordered = withContext(Dispatchers.Default) { sortTracks(snapshot, field, descending) }
                applySortedPlaylist(ordered)
                persistPlaylist()
            }
        } else {
            persistPlaylist()
        }
    }

    // 切换指定曲目的收藏状态：仅就地更新收藏标记，不改变列表顺序
    fun toggleFavorite(trackId: Long) {
        val newLiked = if (likedIds.contains(trackId)) likedIds - trackId else likedIds + trackId
        likedIds = newLiked
        playlist = playlist.map { if (it.id == trackId) it.copy(isFavorite = trackId in newLiked) else it }
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
    }

    // 封面写入成功后自增，通知封面组件强制重载最新封面；
    // 同时作废索引曲目的系统略缩图缓存与非索引曲目的内嵌封面缓存：旧位图与旧结论均已失效
    fun bumpCoverRevision() {
        coverRevision++
        SystemThumbnailCache.clear()
        EmbeddedCoverCache.clear()
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
        val validCurrentIndex = from.takeIf { it in playlist.indices } ?: 0
        return when {
            playMode == PlayMode.RepeatOne && repeatOne -> validCurrentIndex
            playMode == PlayMode.Shuffle -> {
                if (playlist.size == 1) 0
                else playlist.indices.filter { it != validCurrentIndex }.random()
            }
            direction < 0 -> (validCurrentIndex - 1 + playlist.size) % playlist.size
            else -> (validCurrentIndex + 1) % playlist.size
        }
    }

    private fun autoNextIndex(): Int = calculateIndex(direction = 1, repeatOne = true)

    // 下一首索引
    fun nextIndex(): Int = calculateIndex(direction = 1, repeatOne = false)

    // 上一首索引
    fun previousIndex(): Int = calculateIndex(direction = -1, repeatOne = false)

    // ===== UI 层状态写入口：悬浮窗 UI 统一通过这些方法写入状态，避免直接对 public var 赋值 =====
    // 方法与属性 setter 同名会冲突，故用 @JvmName 指定不同 JVM 名
    @JvmName("updatePlayMode")
    fun setPlayMode(mode: PlayMode) { playMode = mode }
    @JvmName("updatePlaybackSpeed")
    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(PLAYBACK_SPEED_MIN, PLAYBACK_SPEED_MAX)
        mediaController?.setPlaybackSpeed(playbackSpeed)
        persistPlaybackSpeed()
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
    fun setTimerMinutes(minutes: Int) { timerMinutes = minutes }
    @JvmName("updateTimerAutoStopped")
    fun setTimerAutoStopped(stopped: Boolean) { timerAutoStopped = stopped }
    @JvmName("updateSleepTimerExpired")
    fun setSleepTimerExpired(expired: Boolean) { sleepTimerExpired = expired }
    @JvmName("updateCurrentPosition")
    fun setCurrentPosition(position: Long) {
        // 拖动进度条直接改写位置：复位单调基准，避免被钳回拖动前的位置
        lastMonoMediaId = null
        currentPosition = position
    }
}

// 播放状态持久化快照：调用时刻即采集，避免写入协程回读时状态已被后续流程（如 release）清空
private data class SavedPlaybackState(
    val audioUri: String,
    val position: Long,
    val mode: Int,
)
