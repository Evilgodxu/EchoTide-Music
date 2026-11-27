package com.yichao.evilgodxu.service

import android.app.NotificationManager
import android.content.Intent
import android.view.KeyEvent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yichao.evilgodxu.App
import com.yichao.evilgodxu.data.music.playback.DecodedOutputFormat
import com.yichao.evilgodxu.data.music.playback.DirectOutputDoNotDisturb
import com.yichao.evilgodxu.data.music.playback.ExclusiveVolume
import com.yichao.evilgodxu.data.music.playback.ExclusiveVolumePlayer
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PerDeviceAudioSink
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.UsbDirectOutput
import com.yichao.evilgodxu.data.music.playback.engine.EngineAudioTrackInfo
import com.yichao.evilgodxu.data.music.playback.engine.EngineListener
import com.yichao.evilgodxu.data.music.playback.engine.EnginePlaybackState
import com.yichao.evilgodxu.data.music.playback.engine.ExoPlayerEngine
import com.yichao.evilgodxu.data.music.playback.engine.PlayWhenReadyChangeReason
import com.yichao.evilgodxu.data.music.playback.engine.PlaybackEngine
import com.yichao.evilgodxu.data.music.playback.handleEndOfMediaItem
import com.yichao.evilgodxu.data.music.playback.handleTracksChanged
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.usb.UsbExclusiveAudioSink
import com.yichao.evilgodxu.data.music.playback.usb.UsbExclusiveOutput
import com.yichao.evilgodxu.data.music.playback.usb.UsbExclusiveState
import com.yichao.evilgodxu.data.music.playback.usb.UsbOutputSink
import com.yichao.evilgodxu.data.settings.exclusiveVolumeStepFlow
import com.yichao.evilgodxu.data.settings.saveExclusiveVolumeStep
import com.yichao.evilgodxu.data.settings.usbDirectOutputModeFlow
import com.yichao.evilgodxu.data.settings.usbExclusiveOutputModeFlow
import com.yichao.evilgodxu.data.settings.volumeGainEnabledFlow
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    /** 播放引擎：播放器依赖集中于一处 */
    private lateinit var engine: PlaybackEngine
    // 服务由系统创建，经 Application 取单例
    private val playbackState: MusicPlaybackState
        get() = (application as App).container.playbackState
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    /** USB 直出：钉到解码器并按源格式开专用输出流 */
    private lateinit var usbDirectOutput: UsbDirectOutput
    /** USB 独占：接口收归本应用，与直出互斥 */
    private lateinit var usbExclusiveOutput: UsbExclusiveOutput
    /** 输出路由：平台与独占二选一；让出设备须与拆流同趟 */
    private lateinit var outputSink: UsbOutputSink
    /** 独占路径的自持音量：绕开混音器，音量键经会话落到此处 */
    private val exclusiveVolume = ExclusiveVolume()

    /** 把自持音量接到会话，独占期间音量键归应用 */
    private lateinit var exclusiveVolumePlayer: ExclusiveVolumePlayer

    /** 音量落盘的延时任务：合并长按的连续改档 */
    private var persistVolumeJob: Job? = null
    /** 直出期间的系统免打扰：随输出成色进出 */
    private lateinit var directOutputDoNotDisturb: DirectOutputDoNotDisturb
    // 引擎与 AudioTrack 仅在主线程访问
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 失焦前是否在播放：恢复后据此续播 */
    private var resumeAfterFocusLoss = false
    /** 是否在一轮失焦中：避免后续失焦覆盖播放意图 */
    private var focusLossActive = false
    private val audioFocusHandler = Handler(Looper.getMainLooper())

    /** 抢占焦点时暂停，恢复后按需续播 */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusLossActive = false
                // 先收回解码器再续播，否则开头会走平台路径
                reclaimExclusiveDevice()
                if (resumeAfterFocusLoss) {
                    resumeAfterFocusLoss = false
                    engine.setPlayWhenReady(true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusLossActive = false
                resumeAfterFocusLoss = false
                engine.pause()
                releaseExclusiveDevice()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 只在首次失焦记播放意图，避免被前次暂停态覆盖
                val firstLossInRound = !focusLossActive
                if (firstLossInRound) {
                    resumeAfterFocusLoss = engine.isPlaying
                    focusLossActive = true
                }
                engine.pause()
                // 让出设备也只做一次，避免反复解绑
                if (firstLossInRound) releaseExclusiveDevice()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        // 两路上报出口合一，避免互相覆盖
        // 回调在播放线程，回写状态无需切线程
        val reportOutputVariant: (Boolean) -> Unit = { floatOutput ->
            playbackState.audioSinkFloatOutput = floatOutput
        }
        // 音频轨回调同在播放线程
        val reportOutputEncoding: (Int?) -> Unit = { encoding ->
            playbackState.audioSinkOutputEncoding = encoding
            // 输出建成后重算直出路由，否则端口查询为空
            if (encoding != null && ::usbDirectOutput.isInitialized) {
                usbDirectOutput.onOutputEstablished()
            }
        }
        val reportAudioTrack: (android.media.AudioTrack?) -> Unit = { track ->
            playbackState.audioTrack = track
        }
        // 按解码输出的 PCM 编码挑直出属性
        val reportDecodedFormat: (Int, Int, Int) -> Unit = { sampleRate, channelCount, pcmEncoding ->
            // 直出挑条目与信息面板共用
            playbackState.audioSinkDecodedFormat =
                DecodedOutputFormat(sampleRate, channelCount, pcmEncoding)
            // 直出较晚装配；格式未变内部自行跳过
            if (::usbDirectOutput.isInitialized) {
                usbDirectOutput.onTrackFormatChanged(sampleRate, channelCount, pcmEncoding)
            }
        }
        // 变体由 sink 自选，此处返回同一实例
        val perDeviceSink = PerDeviceAudioSink(
            directTarget = {
                // 延迟求值；未装配视为未直出
                if (::usbDirectOutput.isInitialized) usbDirectOutput.directTargetDevice() else null
            },
            // 按直出已受理编码选变体；非直出为空
            directOutputEncoding = {
                if (::usbDirectOutput.isInitialized) usbDirectOutput.directOutputEncoding() else null
            },
            // 同族不同率时给出倍率（如 88.2/44.1），否则为 1
            directOutputRateMultiple = {
                if (::usbDirectOutput.isInitialized) usbDirectOutput.directOutputRateMultiple() else 1
            },
            // 建不起音轨时撤销该格式偏好，降级续播
            onDirectOutputUnrealizable = {
                if (::usbDirectOutput.isInitialized) usbDirectOutput.reportUnrealizableFormat()
            },
            onOutputVariantChanged = reportOutputVariant,
            onOutputEncodingChanged = reportOutputEncoding,
            // 轨道自报采样率才是真实写出速率
            onOutputStreamRateChanged = { rate ->
                playbackState.audioSinkOutputSampleRate = rate
            },
            onAudioTrackChanged = reportAudioTrack,
            onDecodedFormatChanged = reportDecodedFormat,
        )
        // 独占与直出在设备层互斥，配置时二选一
        val exclusiveSink = UsbExclusiveAudioSink(
            session = {
                if (::usbExclusiveOutput.isInitialized) usbExclusiveOutput.currentSession() else null
            },
            // 独占绕开混音器，由写出侧施加自持音量
            appVolume = { exclusiveVolume.gain() },
            // 位深按源格式选，未知时退回解码位深
            sourceBitDepth = { playbackState.currentSourceBitDepth },
            // 换曲时界面仍是上一曲，位深按配置里的曲目 id 取
            trackBitDepth = { trackId -> trackId?.let(playbackState::bitDepthOfTrack) },
            // 独占无音轨，编码由写出侧直报；不走直出那条出口
            onOutputEncodingChanged = { encoding ->
                playbackState.audioSinkOutputEncoding = encoding
                // 平台轨道已不参与，清掉旧速率
                playbackState.audioSinkOutputSampleRate = null
            },
        )
        outputSink = UsbOutputSink(
            platform = perDeviceSink,
            exclusive = exclusiveSink,
            exclusiveReady = {
                ::usbExclusiveOutput.isInitialized && usbExclusiveOutput.currentSession() != null
            },
            // 链路失效即归还设备，避免重复失败
            onExclusiveFailed = {
                if (::usbExclusiveOutput.isInitialized) usbExclusiveOutput.onStreamFailed()
            },
            // 独占无 PerDeviceAudioSink，由路由代报
            onRouteChanged = { exclusive ->
                playbackState.usbExclusiveRoute = exclusive
                if (exclusive && ::usbExclusiveOutput.isInitialized) {
                    playbackState.usbExclusiveDevice = usbExclusiveOutput.deviceInfo()
                }
            },
            onDecodedFormatChanged = reportDecodedFormat,
            onVariantChanged = reportOutputVariant,
        )
        // 发布取值入口而非值；仅打包整型输出返回非空
        playbackState.audioSinkWrittenFrames = perDeviceSink::writtenOutputFrames
        // 独占无音轨，延迟由写出侧按缓冲算出
        playbackState.usbExclusiveLatency = exclusiveSink::latencyReading
        // 播放器装配全部收在引擎实现里
        engine = ExoPlayerEngine(this, outputSink)
        // 插队队列非空时停在曲末，由队列决定下一首
        playbackState.playNextHoldChanged = { hold -> engine.setHoldAtItemEnd(hold) }
        playbackState.syncPlayNextHold()
        // 接管期间报远端音量，归还即交还系统
        exclusiveVolumePlayer = ExclusiveVolumePlayer(engine.sessionPlayer, exclusiveVolume) { step ->
            persistExclusiveVolume(step)
        }
        // 界面音量入口：与音量键同源，含落盘与播报
        playbackState.exclusiveVolumeStep = { exclusiveVolume.currentStep }
        playbackState.setExclusiveVolumeStep = { step -> exclusiveVolumePlayer.setStepFromUi(step) }
        engine.addListener(object : EngineListener {
            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: PlayWhenReadyChangeReason,
            ) {
                if (playWhenReady) {
                    // 起播即申请焦点，被拒则收回；引擎内置焦点处理已关
                    if (requestAudioFocus()) {
                        // 重新申请焦点无 GAIN 回调，回收设备须挂在此处
                        reclaimExclusiveDevice()
                    } else {
                        engine.pause()
                    }
                } else if (reason == PlayWhenReadyChangeReason.END_OF_MEDIA_ITEM) {
                    // 停在曲末：由插队队列接管下一首
                    handleEndOfMediaItem(playbackState)
                }
            }

            override fun onError(errorCodeName: String, message: String?, cause: Throwable?) {
                // 输出初始化或写入失败只表现为自动暂停，故记日志
                CrashLogManager.logException(
                    "MusicPlaybackService",
                    "播放失败：$errorCodeName，$message",
                    cause,
                )
            }

            override fun onPlaybackStateChanged(state: EnginePlaybackState) {
                // 输出拆解后清空写出状态，避免信息面板残留
                if (state == EnginePlaybackState.IDLE) {
                    this@MusicPlaybackService.playbackState.audioSinkFloatOutput = null
                    this@MusicPlaybackService.playbackState.audioSinkOutputEncoding = null
                    this@MusicPlaybackService.playbackState.audioTrack = null
                }
            }

            override fun onTracksChanged(info: EngineAudioTrackInfo?) {
                handleTracksChanged(applicationContext, info, playbackState)
            }

            // 记录解码器名；跨曲复用不重复回调
            override fun onAudioDecoderInitialized(decoderName: String) {
                playbackState.audioDecoderName = decoderName
            }

            // 起播意图由入口决定，避免 seek 或换列表自动起播
        })
        mediaSession = MediaSession.Builder(this, SkipProxyPlayer(exclusiveVolumePlayer))
            .setCallback(sessionCallback)
            .build()
        // 直出在会话建立后装配；档位取自设备声明
        usbDirectOutput = UsbDirectOutput(
            engine = engine,
            audioManager = audioManager,
            sourceBitDepth = { playbackState.currentSourceBitDepth },
        )
        // 免打扰随直出进出；无权限时类内跳过
        val notificationManager = getSystemService(NotificationManager::class.java)
        directOutputDoNotDisturb = DirectOutputDoNotDisturb(
            isAccessGranted = { notificationManager.isNotificationPolicyAccessGranted },
            readFilter = { notificationManager.currentInterruptionFilter },
            writeFilter = { notificationManager.setInterruptionFilter(it) },
        )
        // 成色由直出自行判定，不以开关代替
        usbDirectOutput.onRoutingChanged = { mode ->
            playbackState.directOutputMode = mode
            // 成色曲中变化时补一次重配，未变自行跳过
            perDeviceSink.syncRouting()
            // 回调跨线程，投递主线程统一状态
            serviceScope.launch { directOutputDoNotDisturb.onModeChanged(mode) }
        }
        serviceScope.launch {
            usbDirectOutputModeFlow().collect { enabled ->
                usbDirectOutput.setEnabled(enabled)
                // 开关状态同步给播放状态，供心动模式判断
                playbackState.usbDirectOutputEnabled = enabled
                playbackState.highlightPolicy.onImmersiveOutputChanged()
            }
        }
        // 独占自订阅接入与授权，此处只喂开关；成色不由开关代替
        usbExclusiveOutput = UsbExclusiveOutput(
            context = this,
            onStateChanged = { state ->
                val claimed = state is UsbExclusiveState.Active
                // 设备信息随接管与归还同步，交还后清空
                playbackState.usbExclusiveDevice = usbExclusiveOutput.deviceInfo()
                playbackState.usbExclusiveClaimed = claimed
                // 接管即切远端音量；会话状态仅在播放器线程刷新，故投递
                serviceScope.launch { exclusiveVolumePlayer.setRemote(claimed) }
                // 成色一变就地改道，否则本曲余下落在扬声器
                outputSink.syncRouting()
            },
            // 接管三步须同趟，交由写出路由执行
            beforeClaim = outputSink::runTakeover,
        )
        // 开关开着不等于接管成功，成色另由路由上报
        serviceScope.launch {
            usbExclusiveOutputModeFlow().collect { enabled ->
                usbExclusiveOutput.setEnabled(enabled)
                playbackState.usbExclusiveEnabled = enabled
                playbackState.highlightPolicy.onImmersiveOutputChanged()
            }
        }
        // 增益开关落到运行时，施加只在此处
        serviceScope.launch {
            volumeGainEnabledFlow().collect { playbackState.setVolumeGainEnabled(it) }
        }
        // 起播前恢复上次音量档位
        serviceScope.launch {
            applicationContext.exclusiveVolumeStepFlow().first()?.let(exclusiveVolumePlayer::restoreStep)
        }
        // 源位深落定后重算路由；须等格式归属当前曲目
        serviceScope.launch {
            snapshotFlow {
                playbackState.isAudioSignalPathCurrent to playbackState.currentSourceBitDepth
            }
                .distinctUntilChanged()
                .collect { (current, _) ->
                    if (!current) return@collect
                    usbDirectOutput.onSourceBitDepthSettled()
                    perDeviceSink.syncRouting()
                    outputSink.syncRouting()
                }
        }
    }

    /** 延时合并长按改档，仅末次落盘；进程被杀只丢最近一次 */
    private fun persistExclusiveVolume(step: Int) {
        persistVolumeJob?.cancel()
        persistVolumeJob = serviceScope.launch {
            delay(VOLUME_PERSIST_DELAY_MS)
            applicationContext.saveExclusiveVolumeStep(step)
        }
    }

    /** 音量落盘合并窗口 */
    private companion object {
        const val VOLUME_PERSIST_DELAY_MS = 500L
    }

    /** 拦截媒体键的上一首/下一首 */
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

    /** 会话播放器包装：切歌走应用逻辑，而非回到曲首 */
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
        val state = playbackState
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val prev = state.previousIndex()
            if (prev >= 0) {
                state.playbackScope.launch {
                    // 媒体键同样传切歌类型，界面据此直接替换
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
        val state = playbackState
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val next = state.nextIndex()
            if (next >= 0) {
                state.playbackScope.launch {
                    // 下一曲同样按有方向切歌
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

    private fun requestAudioFocus(): Boolean {
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 直出压低音量会破坏格式，故 duck 时暂停
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(audioFocusListener, audioFocusHandler)
            .build()
            .also { audioFocusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            audioFocusRequest = null
        }
    }

    /** 失焦归还解码器：继续持有会卡住系统音频栈 */
    private fun releaseExclusiveDevice() {
        if (!::usbExclusiveOutput.isInitialized) return
        // 拆流与归还须同趟，经路由代劳
        if (::outputSink.isInitialized) {
            outputSink.runRelease { usbExclusiveOutput.releaseForFocusLoss() }
        } else {
            usbExclusiveOutput.releaseForFocusLoss()
        }
    }

    private fun reclaimExclusiveDevice() {
        if (::usbExclusiveOutput.isInitialized) usbExclusiveOutput.reclaimAfterFocusLoss()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        // 会话推进中不停止服务，网络抖动会短暂回落 IDLE
        if (!isPlaybackOngoing && !isPlaybackInProgress()) {
            stopSelf()
        }
    }

    // playWhenReady 或缓冲中即会话推进
    private fun isPlaybackInProgress(): Boolean =
        engine.playWhenReady || engine.playbackState == EnginePlaybackState.BUFFERING

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 同通知路径：缓冲中仍算推进，不误停后台播放
        if (!isPlaybackOngoing || !isPlaybackInProgress()) {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        abandonAudioFocus()
        engine.stop()
        mediaSession?.release()
        mediaSession = null
        stopSelf()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        serviceScope.cancel()
        usbDirectOutput.release()
        // 独占必须显式归还，否则内核驱动一直解绑
        if (::usbExclusiveOutput.isInitialized) usbExclusiveOutput.close()
        // 音量入口随服务失效，避免读到已释放播放器
        playbackState.exclusiveVolumeStep = null
        playbackState.setExclusiveVolumeStep = null
        // 作用域已取消，免打扰在此兜底还原
        directOutputDoNotDisturb.release()
        mediaSession?.release()
        mediaSession = null
        engine.release()
        super.onDestroy()
    }
}
