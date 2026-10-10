package com.yichao.evilgodxu.service

import android.app.NotificationManager
import android.content.Intent
import android.view.KeyEvent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yichao.evilgodxu.App
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.data.music.playback.DecodedOutputFormat
import com.yichao.evilgodxu.data.music.playback.DirectOutputDoNotDisturb
import com.yichao.evilgodxu.data.music.playback.PerDeviceAudioSink
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.UsbDirectOutput
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.settings.usbDirectOutputModeFlow
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    // 系统创建的服务无法构造注入，经 Application 取共享单例
    private val stateHolder: MusicPanelStateHolder
        get() = (application as App).stateHolder
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    /** USB 直出：把播放钉到 USB 解码器并申请位完美传输 */
    private lateinit var usbDirectOutput: UsbDirectOutput
    /** 直出期间的系统免打扰：随输出成色进出 */
    private lateinit var directOutputDoNotDisturb: DirectOutputDoNotDisturb
    // 播放设置的读取与直出都要求主线程：ExoPlayer 与其 AudioTrack 均只在主线程访问
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 焦点丢失前是否正在播放：恢复焦点后据此自动续播 */
    private var resumeAfterFocusLoss = false
    private val audioFocusHandler = Handler(Looper.getMainLooper())

    /** 其他应用抢占焦点时的响应：一律暂停，恢复后按需续播，避免压低音量后不恢复导致的静音 */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterFocusLoss) {
                    resumeAfterFocusLoss = false
                    player.setPlayWhenReady(true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocusLoss = false
                // 焦点丢失会暂停播放，界面上的表现与「自己停了」无从区分，故留痕区分二者
                CrashLogManager.logInfo("MusicPlaybackService", "音频焦点丢失（永久），已暂停播放")
                player.pause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterFocusLoss = player.isPlaying
                CrashLogManager.logInfo("MusicPlaybackService", "音频焦点被临时抢占，已暂停播放")
                player.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        // 音频输出由 PerDeviceAudioSink 按目标设备挑选浮点/整型变体并自行重建，
        // 故此处忽略工厂给出的浮点参数，固定返回同一实例供渲染器使用
        val audioSink = PerDeviceAudioSink(
            context = this,
            audioManager = audioManager,
            directTarget = {
                // 直出在播放器之后装配，此处延迟求值；尚未装配时视为未直出
                if (::usbDirectOutput.isInitialized) usbDirectOutput.directTargetDevice() else null
            },
            // 变体切换发生在渲染器重配点，即 ExoPlayer 的播放线程，回写共享状态无需切线程
            onOutputVariantChanged = { floatOutput ->
                stateHolder.state.audioSinkFloatOutput = floatOutput
            },
            // 音频轨的创建与释放同样在播放线程回调，口径同上
            onOutputEncodingChanged = { encoding ->
                stateHolder.state.audioSinkOutputEncoding = encoding
            },
            onAudioTrackChanged = { track ->
                stateHolder.state.audioTrack = track
            },
            // 直出的混音器属性按解码头输出的真实 PCM 编码挑选。只有这里拿得到它：
            // 容器格式（轨道回调）对压缩源只给采样率与声道，pcmEncoding 仍是 NO_VALUE，
            // 用它挑出的条目与实际写出的编码未必一致，故改由音频输出在重配时上报
            onDecodedFormatChanged = { sampleRate, channelCount, pcmEncoding ->
                // 同一处落地两份用途：直出据此挑条目，音频信息面板据此展示解码输出
                stateHolder.state.audioSinkDecodedFormat =
                    DecodedOutputFormat(sampleRate, channelCount, pcmEncoding)
                // 直出在播放器之后装配，尚未装配时无从下发；格式未变时内部会跳过重复下发
                if (::usbDirectOutput.isInitialized) {
                    usbDirectOutput.onTrackFormatChanged(sampleRate, channelCount, pcmEncoding)
                }
            },
        )
        // 已写入音频轨的帧数随播放持续变化，故发布取值入口而非取值本身：
        // 该入口只在能给出这一读数的变体（打包整型输出）生效时返回非空
        stateHolder.state.audioSinkWrittenFrames = audioSink::writtenOutputFrames
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean,
            ): AudioSink = audioSink
        }
        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    // 空间化是平台唯一可能默认开启的后处理（虚拟环绕/头部追踪），音乐播放追求
                    // 原样保真，显式禁用；音效类（均衡器等）本应用从不挂载，aux 效果默认即禁用
                    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                    .build(),
                false
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // 熄屏保活：在线音源经网络拉流，需同时持有 CPU 唤醒锁与 WLAN 锁（WAKE_MODE_NETWORK）。
        // 仅 WAKE_MODE_LOCAL 时 WLAN 锁不启用，熄屏后 Wi-Fi 进入省电、缓冲停滞，
        // 长时间无进展会被系统回收播放进程
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        // 纯音频播放：显式禁用视频轨道。默认渲染器按视频在前的顺序建组，含内嵌视频轨的文件
        // （MV、带画面的 MP4）会选中视频轨并解码——既白耗解码与播放线程，也会让轨道信息读到视频格式
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) requestAudioFocus()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 播放错误此前无处留痕：音频输出初始化或写入失败会被媒体3 升级为致命错误，
                // 界面上的表现只是「自动暂停」，日志里空无一物。错误码与原因在此一并记下
                CrashLogManager.logException(
                    "MusicPlaybackService",
                    "播放失败：${error.errorCodeName}，${error.message}",
                    error,
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 输出已拆解（停止、释放或加载失败）：上一次的浮点写出与写出编码都不再成立，
                // 清空以免音频信息停留在已不存在的输出链路上
                if (playbackState == Player.STATE_IDLE) {
                    stateHolder.state.audioSinkFloatOutput = null
                    stateHolder.state.audioSinkOutputEncoding = null
                    stateHolder.state.audioTrack = null
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // 只取被选中的音频轨道自身的格式：不能取首个「有选中项」的组——渲染器按视频在前的
                // 顺序建组，组内含视频轨时会先命中视频格式；也不能固定取组内 0 号轨——多音轨时选中项
                // 未必在 0 号，取错会把视频或其它音轨的采样率与声道喂给音频输出
                val format = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                    ?.let { group ->
                        (0 until group.length)
                            .firstOrNull { group.isTrackSelected(it) }
                            ?.let(group::getTrackFormat)
                    }
                val state = stateHolder.state
                val currentTrack = state.currentTrack
                // 每次轨道切换后按解码格式更新信号路径状态
                val fileFormat = format?.let { f ->
                    currentTrack?.path
                        ?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() }
                        ?.uppercase()
                        ?.let { if (it == "MPEG") "MP3" else it }
                        ?: TrackAudioInfoReader.mimeToFormatName(f.sampleMimeType)
                }
                if (format != null) {
                    // 采样率/声道取解码格式的实际值，缺失项留空交由展示层跳过，不用固定值顶替
                    val decodedSampleRate = format.sampleRate.takeIf { it > 0 }
                    // 优先沿用源格式预读的位深/声道，保证播放前后展示一致不跳变；
                    // 位深是源文件属性，不以解码输出位深推算（高解析度曲目解码常以浮点输出）。
                    // 归属判定含音频源 URI：无损升级换源后曲目 ID 不变，沿用旧值会把有损位深/声道带过来
                    val sourceFormat = state.audioSignalPathFormat
                        .takeIf {
                            state.audioSignalPathTrackId == currentTrack?.id &&
                                state.audioSignalPathSourceUri == currentTrack?.audioUri
                        }
                    val bitDepth = sourceFormat?.bitDepth
                        ?: currentTrack?.takeIf { it.isLocalAudioSource }
                            ?.let { TrackAudioInfoReader.readContainerFormat(applicationContext, it)?.bitDepth }
                    state.audioSignalPathFormat = AudioSignalPathFormat(
                        format = fileFormat,
                        sampleRate = decodedSampleRate,
                        outputRate = decodedSampleRate,
                        bitDepth = bitDepth,
                        channels = sourceFormat?.channels ?: format.channelCount.takeIf { it > 0 },
                        // Format.bitrate 单位为 bps，统一换算为 kbps；VBR 曲目 bitrate 未知时回退 averageBitrate
                        bitrate = maxOf(format.bitrate, format.averageBitrate)
                            .takeIf { it > 0 }
                            ?.let { it / 1000 },
                    )
                    state.audioSignalPathTrackId = currentTrack?.id
                    state.audioSignalPathSourceUri = currentTrack?.audioUri
                }
                // 解码头未给出比特率时（FLAC/VBR 常见），异步读取真实比特率并回填；
                // 仅本地音频源读取文件元数据，在线曲目等缓存完成后由 refreshTrackFormatInfoFromLocal 补齐
                if (state.audioSignalPathFormat?.bitrate == null) {
                    val track = currentTrack
                    state.playbackScope.launch(Dispatchers.IO) {
                        if (track != null && track.isLocalAudioSource &&
                            state.audioSignalPathTrackId == track.id &&
                            state.audioSignalPathSourceUri == track.audioUri
                        ) {
                            TrackAudioInfoReader.readBitrateKbps(applicationContext, track)?.let { bitrate ->
                                if (state.audioSignalPathFormat?.bitrate == null) {
                                    state.audioSignalPathFormat = state.audioSignalPathFormat?.copy(bitrate = bitrate)
                                }
                            }
                        }
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    player.playWhenReady = true
                }
            }
        })
        // 记录平台实际使用的解码器实现名，供音频信息展示。
        // 解码器可跨曲复用，复用时不重复回调，故该值始终对应当前渲染器正在使用的解码器
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                stateHolder.state.audioDecoderName = decoderName
            }
        })
        mediaSession = MediaSession.Builder(this, SkipProxyPlayer(player))
            .setCallback(sessionCallback)
            .build()
        // 直出不参与媒体会话，在会话建立后单独装配；设置变更即刻生效，无需重启服务
        usbDirectOutput = UsbDirectOutput(player, audioManager)
        // 免打扰随输出成色进出：位完美与源格式直出都属于专注聆听，通知与提示音是最直接的打扰源；
        // 未获免打扰访问权时类内自行跳过，不影响播放
        val notificationManager = getSystemService(NotificationManager::class.java)
        directOutputDoNotDisturb = DirectOutputDoNotDisturb(
            isAccessGranted = { notificationManager.isNotificationPolicyAccessGranted },
            readFilter = { notificationManager.currentInterruptionFilter },
            writeFilter = { notificationManager.setInterruptionFilter(it) },
        )
        // 输出成色由直出自行判定（设备缺失、设备未提供动态混音端口、属性未被系统受理时都退回系统混音），
        // 不能以设置开关代替——开关打开而设备不支撑时播放仍走系统混音
        usbDirectOutput.onRoutingChanged = { mode ->
            stateHolder.state.directOutputMode = mode
            // 直出成立即交回整曲播放：片段首尾的音量淡入淡出在直出链路上无从实施
            stateHolder.state.onDirectOutputModeChanged(mode)
            // 成色回调来自播放线程与主线程两处，投递主线程使 holding 与还原档位这对状态同处一条线
            serviceScope.launch { directOutputDoNotDisturb.onModeChanged(mode) }
        }
        serviceScope.launch {
            usbDirectOutputModeFlow().collect { enabled ->
                usbDirectOutput.setEnabled(enabled)
            }
        }
    }

    /** 拦截系统媒体面板和耳机/蓝牙媒体键的上一首/下一首操作 */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        handlePreviousTrack()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                        handleNextTrack()
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }

    /**
     * 包装 ExoPlayer，把系统媒体面板/通知栏的上一首/下一首操作
     * 映射到应用自己的切歌逻辑，避免默认行为中「回退到当前曲目开头」。
     */
    private inner class SkipProxyPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToPrevious() {
            handlePreviousTrack()
        }

        override fun seekToPreviousMediaItem() {
            handlePreviousTrack()
        }

        override fun seekToNext() {
            handleNextTrack()
        }

        override fun seekToNextMediaItem() {
            handleNextTrack()
        }
    }

    private fun handlePreviousTrack() {
        val state = stateHolder.state
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val prev = state.previousIndex()
            if (prev >= 0) {
                state.playbackScope.launch {
                    // 媒体键的上一曲与界面按钮同为有方向的切歌：漏传类型会落到默认的选曲播放，
                    // 界面据此不做自然移入移走，而是直接替换
                    playTrackAt(
                        this@MusicPlaybackService,
                        state,
                        prev,
                        switchKind = TrackSwitchKind.Previous,
                    )
                }
            }
        }
    }

    private fun handleNextTrack() {
        val state = stateHolder.state
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val next = state.nextIndex()
            if (next >= 0) {
                state.playbackScope.launch {
                    // 同上一曲：媒体键的下一曲也按有方向的切歌处理
                    playTrackAt(
                        this@MusicPlaybackService,
                        state,
                        next,
                        switchKind = TrackSwitchKind.Next,
                    )
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener, audioFocusHandler)
            .build()
            .also { audioFocusRequest = it }
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            audioFocusRequest = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        // 播放会话仍在推进（含缓冲中与曲目间隙）时不得停止服务：
        // 网络抖动会让播放器短暂回落 STATE_IDLE，此时结束服务等于直接终结后台播放
        if (!isPlaybackOngoing && !isPlaybackInProgress()) {
            stopSelf()
        }
    }

    // 播放是否仍在推进：playWhenReady 为真表示会话未结束，需保留服务等待续播
    private fun isPlaybackInProgress(): Boolean =
        player.playWhenReady || player.playbackState == Player.STATE_BUFFERING

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 与 media3 默认语义一致：后台播放进行中保留服务，否则释放播放资源并停止
        if (!isPlaybackOngoing || !player.isPlaying) {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        abandonAudioFocus()
        player.stop()
        mediaSession?.release()
        mediaSession = null
        stopSelf()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        serviceScope.cancel()
        usbDirectOutput.release()
        // 服务销毁时成色回调已无从投递（作用域已取消），免打扰在此直接兜底还原
        directOutputDoNotDisturb.release()
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }
}
