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
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
import com.yichao.evilgodxu.data.music.playback.alac.AlacAudioRenderer
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
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.ArrayList

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    // 系统创建的服务无法构造注入，经 Application 取共享单例
    private val playbackState: MusicPlaybackState
        get() = (application as App).playbackState
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    /** USB 直出：把播放钉到 USB 解码器，并为播放申请按源格式打开的专用输出流 */
    private lateinit var usbDirectOutput: UsbDirectOutput
    /** USB 独占：把 USB 音频接口收归本应用（内核驱动随之解绑），与直出互斥的第二条路 */
    private lateinit var usbExclusiveOutput: UsbExclusiveOutput
    /**
     * 独占路径的应用自持音量。独占绕开混音器后系统音量不再作用于这条流，响度只能自己做，
     * 音量键经会话的远端音量通道落到它身上——见 [ExclusiveVolume] 与 [ExclusiveVolumePlayer]。
     */
    private val exclusiveVolume = ExclusiveVolume()

    /** 把 [exclusiveVolume] 接到媒体会话上：独占成立期间自报远端设备音量，音量键即归应用 */
    private lateinit var exclusiveVolumePlayer: ExclusiveVolumePlayer

    /** 音量落盘的延时任务：长按音量键会连续改档，合并到最后一次取值再写盘 */
    private var persistVolumeJob: Job? = null
    /** 直出期间的系统免打扰：随输出成色进出 */
    private lateinit var directOutputDoNotDisturb: DirectOutputDoNotDisturb
    // 播放设置的读取与直出都要求主线程：ExoPlayer 与其 AudioTrack 均只在主线程访问
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 焦点丢失前是否正在播放：恢复焦点后据此自动续播 */
    private var resumeAfterFocusLoss = false
    /** 当前是否处于一轮临时失焦中：连续多次失焦不得用后来的暂停态覆盖最初的播放意图 */
    private var focusLossActive = false
    private val audioFocusHandler = Handler(Looper.getMainLooper())

    /** 其他应用抢占焦点时的响应：一律暂停，恢复后按需续播，避免压低音量后不恢复导致的静音 */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusLossActive = false
                if (resumeAfterFocusLoss) {
                    resumeAfterFocusLoss = false
                    player.setPlayWhenReady(true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusLossActive = false
                resumeAfterFocusLoss = false
                player.pause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 只在一轮失焦的第一次记下播放意图：此时可能已被前一次失焦暂停，
                // 用当前的 isPlaying 覆盖会把「等会儿续播」改成「不续播」
                if (!focusLossActive) {
                    resumeAfterFocusLoss = player.isPlaying
                    focusLossActive = true
                }
                player.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        // 两条路各自的上报出口收在同一处：同一批状态不该有两个写法，路由切换时也不会互相覆盖
        // 变体切换发生在渲染器重配点，即 ExoPlayer 的播放线程，回写共享状态无需切线程
        val reportOutputVariant: (Boolean) -> Unit = { floatOutput ->
            playbackState.audioSinkFloatOutput = floatOutput
        }
        // 音频轨的创建与释放同样在播放线程回调，口径同上
        val reportOutputEncoding: (Int?) -> Unit = { encoding ->
            playbackState.audioSinkOutputEncoding = encoding
            // 输出建成即重算一次直出路由：平台的动态混音端口查询按输出端口应答，应用没有输出时
            // 只给空表，据此判定会把直出锁在门外；输出建成后再问一次才对得上
            if (encoding != null && ::usbDirectOutput.isInitialized) {
                usbDirectOutput.onOutputEstablished()
            }
        }
        val reportAudioTrack: (android.media.AudioTrack?) -> Unit = { track ->
            playbackState.audioTrack = track
        }
        // 直出的混音器属性按解码头输出的真实 PCM 编码挑选。只有这里拿得到它：
        // 容器格式（轨道回调）对压缩源只给采样率与声道，pcmEncoding 仍是 NO_VALUE，
        // 用它挑出的条目与实际写出的编码未必一致，故改由音频输出在重配时上报
        val reportDecodedFormat: (Int, Int, Int) -> Unit = { sampleRate, channelCount, pcmEncoding ->
            // 同一处落地两份用途：直出据此挑条目，音频信息面板据此展示解码输出
            playbackState.audioSinkDecodedFormat =
                DecodedOutputFormat(sampleRate, channelCount, pcmEncoding)
            // 直出在播放器之后装配，尚未装配时无从下发；格式未变时内部会跳过重复下发
            if (::usbDirectOutput.isInitialized) {
                usbDirectOutput.onTrackFormatChanged(sampleRate, channelCount, pcmEncoding)
            }
        }
        // 音频输出由 PerDeviceAudioSink 按直出已受理的编码挑选浮点/整型变体并自行重建，
        // 故此处忽略工厂给出的浮点参数，固定返回同一实例供渲染器使用
        val perDeviceSink = PerDeviceAudioSink(
            context = this,
            directTarget = {
                // 直出在播放器之后装配，此处延迟求值；尚未装配时视为未直出
                if (::usbDirectOutput.isInitialized) usbDirectOutput.directTargetDevice() else null
            },
            // 写出变体按直出已受理的编码选定，与下发并被系统受理的那条属性逐字段对齐；
            // 不是直出时为空，变体保持浮点
            directOutputEncoding = {
                if (::usbDirectOutput.isInitialized) usbDirectOutput.directOutputEncoding() else null
            },
            // 直出档位与源同族却不同率时（设备只声明 88.2、本条是 44.1）给出倍率：音频输出据此把数据
            // 插值放大、并按倍率开音轨（见 UsbDirectOutput 与 SameFamilyUpsampler）；
            // 未直出、或直出档位与源同率时为 1，该路径整体不参与
            directOutputRateMultiple = {
                if (::usbDirectOutput.isInitialized) usbDirectOutput.directOutputRateMultiple() else 1
            },
            // 打包整型输出建不起音频轨时回报直出侧：撤销该格式偏好并记为不可用，播放降级续走
            onDirectOutputUnrealizable = {
                if (::usbDirectOutput.isInitialized) usbDirectOutput.reportUnrealizableFormat()
            },
            onOutputVariantChanged = reportOutputVariant,
            onOutputEncodingChanged = reportOutputEncoding,
            // 轨道自报的采样率：同族倍率档下它才是真实写出速率，按源格式推算会把 88.2 说成 44.1
            onOutputStreamRateChanged = { rate ->
                playbackState.audioSinkOutputSampleRate = rate
            },
            onAudioTrackChanged = reportAudioTrack,
            onDecodedFormatChanged = reportDecodedFormat,
        )
        // 独占与平台直出在设备层面互斥（接口同一时刻只能有一个持有者），故在其上再套一层路由：
        // 每次配置时按"独占是否已就绪 + 本次格式能否原样送出"二选一，两条路各自完整，不叠加
        val exclusiveSink = UsbExclusiveAudioSink(
            session = {
                if (::usbExclusiveOutput.isInitialized) usbExclusiveOutput.currentSession() else null
            },
            // 独占绕开混音器，故由写出侧施加应用自持的音量；未装配时按满幅，不影响其它路径
            appVolume = { exclusiveVolume.gain() },
            // 写出位深按源格式选；源位深未知时退回解码输出位深
            sourceBitDepth = { playbackState.currentSourceBitDepth },
            // 独占这条路上没有音频轨可供平台回填写出编码，故由写出侧直接上报；
            // 不走 reportOutputEncoding：那条出口还兼着"让直出重算路由"的职责，而独占期间直出并不参与
            onOutputEncodingChanged = { encoding ->
                playbackState.audioSinkOutputEncoding = encoding
                // 平台侧那条轨道已不参与这条链路，留着它报过的速率只会误导面板
                playbackState.audioSinkOutputSampleRate = null
            },
        )
        val audioSink = UsbOutputSink(
            platform = perDeviceSink,
            exclusive = exclusiveSink,
            exclusiveReady = {
                ::usbExclusiveOutput.isInitialized && usbExclusiveOutput.currentSession() != null
            },
            // 独占链路失效即归还设备：留着一个已废的会话只会让每次配置都重走一遍同样的失败
            onExclusiveFailed = {
                if (::usbExclusiveOutput.isInitialized) usbExclusiveOutput.onStreamFailed()
            },
            // 路由成色与解码格式：独占路径没有 PerDeviceAudioSink 那一环，故由路由代它上报
            onRouteChanged = { exclusive ->
                playbackState.usbExclusiveRoute = exclusive
                if (exclusive && ::usbExclusiveOutput.isInitialized) {
                    playbackState.usbExclusiveDevice = usbExclusiveOutput.deviceInfo()
                }
            },
            onDecodedFormatChanged = reportDecodedFormat,
            onVariantChanged = reportOutputVariant,
        )
        // 已写入音频轨的帧数随播放持续变化，故发布取值入口而非取值本身：
        // 该入口只在能给出这一读数的变体（打包整型输出）生效时返回非空
        playbackState.audioSinkWrittenFrames = perDeviceSink::writtenOutputFrames
        // 独占路径没有音频轨可读，延迟读数只能由写出侧按自己的缓冲算出；不在那条路上时它自行返回 null
        playbackState.usbExclusiveLatency = exclusiveSink::latencyReading
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean,
            ): AudioSink = audioSink

            /**
             * 补上系统缺失的 ALAC 解码渲染器。
             *
             * 追加在默认渲染器之后，优先级低于系统渲染器：厂商自带的 `audio/alac` 解码器仍被优先选中。
             * 无解码器时媒体3 会把整条音频轨判为不可解码、播放器不选中该轨——表现为静音走完且不报错。
             */
            override fun buildAudioRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
                out.add(AlacAudioRenderer(eventHandler, eventListener, audioSink))
            }
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
        // 会话侧的设备音量自报：接管期间报远端（音量键归应用），归还设备即交还系统。
        // 播放器一建好就装配，独占接管的回报（设备接入侧）多半晚于此处
        exclusiveVolumePlayer = ExclusiveVolumePlayer(player, exclusiveVolume) { step ->
            persistExclusiveVolume(step)
        }
        // 界面音量手势的读写入口：档位与音量键同源，写回也走同一条路（含落盘与会话播报）
        playbackState.exclusiveVolumeStep = { exclusiveVolume.currentStep }
        playbackState.setExclusiveVolumeStep = { step -> exclusiveVolumePlayer.setStepFromUi(step) }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 在起播意图成立的时刻申请焦点，而不是等音频已经开始播：被拒时立即收回播放意图。
                // 播放器内置焦点处理已关闭（见 setAudioAttributes 的 false），这里不处理就没有第二道保障
                if (playWhenReady && !requestAudioFocus()) {
                    player.pause()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 音频输出初始化或写入失败会被媒体3 升级为致命错误，界面上只表现为「自动暂停」，
                // 故在此把错误码与原因记下
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
                    this@MusicPlaybackService.playbackState.audioSinkFloatOutput = null
                    this@MusicPlaybackService.playbackState.audioSinkOutputEncoding = null
                    this@MusicPlaybackService.playbackState.audioTrack = null
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                handleTracksChanged(applicationContext, player, playbackState, tracks)
            }

            // 起播意图由操作入口决定，过渡回调不再强制 playWhenReady=true：
            // 否则暂停状态下 seek 到另一项、或内部换列表都会被这里拉起来自动播放
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
                playbackState.audioDecoderName = decoderName
            }
        })
        mediaSession = MediaSession.Builder(this, SkipProxyPlayer(exclusiveVolumePlayer))
            .setCallback(sessionCallback)
            .build()
        // 直出不参与媒体会话，在会话建立后单独装配；设置变更即刻生效，无需重启服务。
        // 档位仍取自设备声明；源位深只用于在这些声明里挑与源一致的那一档
        usbDirectOutput = UsbDirectOutput(
            player = player,
            audioManager = audioManager,
            sourceBitDepth = { playbackState.currentSourceBitDepth },
        )
        // 免打扰随输出成色进出：直出属于专注聆听，通知与提示音是最直接的打扰源；
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
            playbackState.directOutputMode = mode
            // 成色在曲中才变化时（开关打开、设备接入、重算后恢复）写出变体还没跟上，补一次重配；
            // 变体未变时该方法自行跳过，故这里无须判断成色
            perDeviceSink.syncRouting()
            // 成色回调来自播放线程与主线程两处，投递主线程使 holding 与还原档位这对状态同处一条线
            serviceScope.launch { directOutputDoNotDisturb.onModeChanged(mode) }
        }
        serviceScope.launch {
            usbDirectOutputModeFlow().collect { enabled ->
                usbDirectOutput.setEnabled(enabled)
                // 开关的启用状态同步给播放状态：心动模式据此判断能否进入，见 isImmersiveOutput
                playbackState.usbDirectOutputEnabled = enabled
                playbackState.highlightPolicy.onImmersiveOutputChanged()
            }
        }
        // 独占同样在会话建立后装配。它自己订阅 USB 的接入/拔出与授权结果，故只需喂开关与成色出口；
        // 成色不由开关代替——开关开着而设备没接入、没授权、接管失败时，播放仍走平台路径
        usbExclusiveOutput = UsbExclusiveOutput(
            context = this,
            onStateChanged = { state ->
                val claimed = state is UsbExclusiveState.Active
                // 设备信息随接管与归还同步：接管期间该设备已从系统音频里消失，面板只能由此取，
                // 交还后随之清空，以免面板继续展示一台已不在手里的设备
                playbackState.usbExclusiveDevice = usbExclusiveOutput.deviceInfo()
                playbackState.usbExclusiveClaimed = claimed
                // 接管即把会话切到远端音量，不等路由成立：设备一旦被接管就已退出系统音频，
                // 系统媒体音量此后不作用于它，此时仍让音量键去改媒体档位只会"按了没反应"。
                // 回调来自主线程或播放线程（链路失效），而会话状态只能在播放器线程上刷新，故一律投递
                serviceScope.launch { exclusiveVolumePlayer.setRemote(claimed) }
                // 成色一变就地改道：接管一成立那台设备就退出了系统音频，平台那条输出写不出去，
                // 等到下次换曲才改道的话，本曲余下的部分只能落在扬声器上；归还之后同理要改回平台路径
                audioSink.syncRouting()
            },
            // 接管的三步（放开平台输出、取设备、改道）要落在播放线程的同一趟里，故改写出的路由代劳
            beforeClaim = audioSink::runTakeover,
        )
        // 独占开关只在此处喂给接管方：接管是否成立由它自己订阅的设备接入与授权结果决定，
        // 开关开着不等于接管成功，成色另由 UsbOutputSink 按实际路由上报
        serviceScope.launch {
            usbExclusiveOutputModeFlow().collect { enabled ->
                usbExclusiveOutput.setEnabled(enabled)
                playbackState.usbExclusiveEnabled = enabled
                playbackState.highlightPolicy.onImmersiveOutputChanged()
            }
        }
        // 上次的档位在起播前恢复：读盘与独占开关的读取走同一份设置，两者都在同一批协程里完成
        serviceScope.launch {
            applicationContext.exclusiveVolumeStepFlow().first()?.let(exclusiveVolumePlayer::restoreStep)
        }
    }

    /**
     * 档位落盘。
     *
     * 延时合并长按产生的连续改动：逐档写盘等于把写盘频率挂在按键重复率上。
     * 音量是非关键数据，中途进程被杀只丢最近一次改动。
     */
    private fun persistExclusiveVolume(step: Int) {
        persistVolumeJob?.cancel()
        persistVolumeJob = serviceScope.launch {
            delay(VOLUME_PERSIST_DELAY_MS)
            applicationContext.saveExclusiveVolumeStep(step)
        }
    }

    /** 音量落盘的合并窗口：长按音量键的重复间隔远小于它，抬手之后才会真正写盘 */
    private companion object {
        const val VOLUME_PERSIST_DELAY_MS = 500L
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
        val state = playbackState
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
        val state = playbackState
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

    private fun requestAudioFocus(): Boolean {
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 直出链路上音量不参与处理，被压低音量等同于破坏输出格式，故声明「被 duck 时暂停」：
            // 框架据此按暂停而非压低音量处理本应用的流，与下面的监听器一律暂停的行为保持一致
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
        // 与通知路径同一口径：playWhenReady 或 BUFFERING 都表示会话仍在推进。
        // 只用 isPlaying 会把「网络缓冲中、仍准备续播」误判为已停止，划掉任务时停掉后台播放
        if (!isPlaybackOngoing || !isPlaybackInProgress()) {
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
        // 独占必须显式归还：接口不还，内核的音频驱动就一直是解绑状态，该解码器在系统音频里始终不出现
        if (::usbExclusiveOutput.isInitialized) usbExclusiveOutput.close()
        // 音量入口指向本服务实例，随服务一起失效；留着会让界面读到一个已释放的播放器
        playbackState.exclusiveVolumeStep = null
        playbackState.setExclusiveVolumeStep = null
        // 服务销毁时成色回调已无从投递（作用域已取消），免打扰在此直接兜底还原
        directOutputDoNotDisturb.release()
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }
}
