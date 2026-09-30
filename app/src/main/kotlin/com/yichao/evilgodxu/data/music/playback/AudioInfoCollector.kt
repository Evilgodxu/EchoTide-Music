package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.analysis.isLosslessFormatName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// USB 解码器会以设备、耳机、配件三类上报，三者都是可直接播放的输出目标
private val USB_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

// 蓝牙音频输出：A2DP 承载媒体音频，SCO 为通话通路，BLE 系列与助听器为低功耗音频设备
private val BLUETOOTH_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID,
)

// 内置扬声器：外放通路
private val SPEAKER_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
)

// 有线通路：耳机与耳麦同为线缆接入
private val WIRED_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
)

// 媒体属性：路由由系统按属性判定，此处与播放器自身的属性（USAGE_MEDIA / CONTENT_TYPE_MUSIC）取同一口径
private val PLAYBACK_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

// 设备支持的采样率最多列出前几项：该行过长时会被展示层截断，取全量只会白占版面
private const val MAX_LISTED_SAMPLE_RATES = 4

/**
 * 当前播放链路的信息采集。
 *
 * 只读取平台与播放器实际给出的值，读不到的项一律留空交由展示层跳过对应行，
 * 不用默认值或推测值顶替，避免把未知项展示成真实信息。
 */
internal object AudioInfoCollector {

    /**
     * 采集信息快照。
     *
     * 播放器接口只能在创建它的线程调用（MediaController 有线程归属），故先在主线程取完
     * 会话 ID 与传输状态，再进入 IO 采集设备信息、蓝牙设备信息与源文件大小。
     */
    suspend fun collect(context: Context, state: MusicPlaybackState): AudioInfoSnapshot {
        val playback = playbackSnapshot(state.player)
        return withContext(Dispatchers.IO) {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val outputs = runCatching {
                audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            }.getOrNull().orEmpty().toList()
            AudioInfoSnapshot(
                sourcePath = sourcePath(state),
                fileSizeBytes = fileSizeBytes(context, state),
                format = state.audioSignalPathFormat
                    .takeIf { state.isAudioSignalPathCurrent }
                    ?.format
                    ?.removePrefix("audio/")
                    ?.takeIf { it.isNotBlank() },
                decoder = state.audioDecoderName?.takeIf { it.isNotBlank() },
                sourceSampleRate = state.sourceSampleRate(),
                outputSampleRate = outputSampleRate(audioManager, state),
                bitrateKbps = state.audioSignalPathFormat
                    ?.takeIf { state.isAudioSignalPathCurrent }
                    ?.bitrate
                    ?.takeIf { it > 0 },
                channelCount = state.sourceChannelCount(),
                bitDepth = state.audioSignalPathFormat
                    ?.takeIf { state.isAudioSignalPathCurrent }
                    ?.bitDepth
                    ?.takeIf { it > 0 },
                lossless = state.audioSignalPathFormat
                    ?.takeIf { state.isAudioSignalPathCurrent }
                    ?.format
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::isLosslessFormatName),
                outputMode = outputMode(state),
                audioSessionId = playback.audioSessionId,
                floatOutput = floatOutputState(state),
                outputEncoding = outputEncoding(state),
                latencyMs = nativeOutputLatencyMs(audioManager),
                transportState = playback.transportState,
                outputDevice = currentOutputDevice(context, audioManager, state, outputs),
            )
        }
    }

    /**
     * 只能从播放器读取的链路项。
     *
     * MediaController 有线程归属，取值必须在其所属线程完成，故统一切到主线程读取；
     * 已在主线程时不会额外派发，避免与调用方互相等待。
     */
    @OptIn(UnstableApi::class)
    private suspend fun playbackSnapshot(player: Player?): PlaybackSnapshot =
        withContext(Dispatchers.Main.immediate) {
            PlaybackSnapshot(
                audioSessionId = player?.audioSessionId?.takeIf { it > 0 },
                transportState = transportState(player),
            )
        }

    private data class PlaybackSnapshot(
        val audioSessionId: Int?,
        val transportState: AudioTransportState?,
    )

    // 音频文件路径：本地文件优先取真实路径，在线音源回退到其 URI，均无值时不展示该行
    private fun sourcePath(state: MusicPlaybackState): String? {
        val track = state.currentTrack ?: return null
        return track.path.takeIf { it.isNotBlank() }
            ?: track.audioUri.takeIf { it.isNotBlank() }
    }

    // 源文件字节数：复用格式读取的大小入口，本地文件与 content URI 都能取得，
    // 在线音源读不到即留空，与其余字段同口径由展示层跳过该行
    private fun fileSizeBytes(context: Context, state: MusicPlaybackState): Long? =
        state.currentTrack
            ?.let { TrackAudioInfoReader.readFileSize(context, it) }
            ?.takeIf { it > 0 }

    // 源采样率：取自当前曲目解码所得的格式信息
    private fun MusicPlaybackState.sourceSampleRate(): Int? =
        audioSignalPathFormat
            ?.takeIf { isAudioSignalPathCurrent }
            ?.sampleRate
            ?.takeIf { it > 0 }

    private fun MusicPlaybackState.sourceChannelCount(): Int? =
        audioSignalPathFormat
            ?.takeIf { isAudioSignalPathCurrent }
            ?.channels
            ?.takeIf { it > 0 }

    // 音频输出模式：位完美独占成立时为直出，否则走系统混音；未装载曲目时输出链路的取向无意义，不展示
    private fun outputMode(state: MusicPlaybackState): AudioOutputMode? {
        if (state.currentTrack == null) return null
        return if (state.bitPerfectOutputActive) AudioOutputMode.BIT_PERFECT else AudioOutputMode.MIXER
    }

    /**
     * 浮点写出状态。
     *
     * 未装载曲目时输出链路的取向无意义，与 [outputMode] 同口径不展示；已装载而输出未建立时保留为
     * 独立状态，交由展示层与「未启用」分开表述。
     */
    private fun floatOutputState(state: MusicPlaybackState): FloatOutputState? {
        if (state.currentTrack == null) return null
        return when (state.audioSinkFloatOutput) {
            true -> FloatOutputState.ENABLED
            false -> FloatOutputState.DISABLED
            null -> FloatOutputState.NOT_ESTABLISHED
        }
    }

    /**
     * 输出编码。
     *
     * 取音频轨被创建时的实际写出编码，解码格式与位完美混音器属性都可能改写它，故不按源格式推算。
     * 未装载曲目时无从谈起，与 [outputMode] 同口径不展示；直通压缩格式等非 PCM 编码无位深可言，
     * 同样不产出条目。
     */
    private fun outputEncoding(state: MusicPlaybackState): OutputEncoding? {
        if (state.currentTrack == null) return null
        return when (state.audioSinkOutputEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> OutputEncoding.PCM_8BIT
            AudioFormat.ENCODING_PCM_16BIT -> OutputEncoding.PCM_16BIT
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputEncoding.PCM_24BIT
            AudioFormat.ENCODING_PCM_32BIT -> OutputEncoding.PCM_32BIT
            AudioFormat.ENCODING_PCM_FLOAT -> OutputEncoding.PCM_FLOAT
            null -> OutputEncoding.NOT_ESTABLISHED
            else -> null
        }
    }

    /**
     * 输出采样率。
     *
     * 位完美独占下数据不经混音器，输出即源采样率；其余情况的输出采样率由系统混音器决定，
     * 只认系统上报值——混音器采样率与源采样率不等即发生重采样，故不能用源采样率冒充输出值。
     * 系统未上报时返回 null，交由展示层跳过该行。
     */
    private fun outputSampleRate(audioManager: AudioManager?, state: MusicPlaybackState): Int? {
        if (state.bitPerfectOutputActive) return state.sourceSampleRate()
        return audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    /**
     * 平均延迟：平台按「输出帧数 ÷ 输出采样率」给出的单缓冲时长，
     * 即音频数据进入输出链路后的平均驻留时间。
     * 输出帧数与采样率任一未上报则无从推算，返回 null。
     */
    private fun nativeOutputLatencyMs(audioManager: AudioManager?): Float? {
        val framesPerBuffer = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        val outputRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        return framesPerBuffer * 1000f / outputRate
    }

    // 传输状态：缓冲与结束属播放器的推进态，优先于起播意愿；空闲态说明输出尚未建立
    private fun transportState(player: Player?): AudioTransportState? = when {
        player == null -> null
        player.playbackState == Player.STATE_BUFFERING -> AudioTransportState.BUFFERING
        player.playbackState == Player.STATE_ENDED -> AudioTransportState.ENDED
        player.playbackState == Player.STATE_IDLE -> AudioTransportState.IDLE
        player.playWhenReady -> AudioTransportState.PLAYING
        else -> AudioTransportState.PAUSED
    }

    /**
     * 当前输出设备。
     *
     * 设备名与蓝牙链路信息分别取自音频栈与蓝牙栈：音频栈给出设备自报名与支持格式，
     * 蓝牙栈才是远端设备名的可靠来源，故蓝牙设备一律以蓝牙栈的名称为准。
     * 判定不出当前输出目标时不产出条目，由展示层跳过对应行。
     */
    private fun currentOutputDevice(
        context: Context,
        audioManager: AudioManager?,
        state: MusicPlaybackState,
        outputs: List<AudioDeviceInfo>,
    ): OutputDeviceInfo? {
        val device = routedOutputDevice(audioManager, state, outputs) ?: return null
        val kind = outputDeviceKind(device.type)
        val address = usableAddress(device.address)
        val bluetooth = if (kind == OutputDeviceKind.BLUETOOTH) {
            BluetoothDeviceResolver.resolve(context, address)
        } else {
            null
        }
        return OutputDeviceInfo(
            kind = kind,
            name = if (kind == OutputDeviceKind.BLUETOOTH) {
                bluetooth?.name
            } else {
                device.productName?.toString()?.takeIf { it.isNotBlank() }
            },
            address = address,
            supportedSampleRates = device.sampleRates.take(MAX_LISTED_SAMPLE_RATES).toList(),
            channelCount = device.channelCounts.firstOrNull()?.takeIf { it > 0 },
            bluetooth = bluetooth?.let {
                BluetoothLinkInfo(linkType = it.linkType, deviceClass = it.deviceClass)
            },
        )
    }

    /**
     * 系统策略判定的当前播放输出设备。
     *
     * 媒体路由由系统按音频属性选出，属性路由查询的首项即实际输出目标（仅在多路重复时才有第二项）。
     * 位完美独占是应用把播放直接钉定到 USB 解码器，该钉定未必反映在策略查询结果中，
     * 故此状态下按 USB 类型取用——独占成立时输出必然是被钉定的那台 USB 解码器。
     */
    private fun routedOutputDevice(
        audioManager: AudioManager?,
        state: MusicPlaybackState,
        outputs: List<AudioDeviceInfo>,
    ): AudioDeviceInfo? {
        if (state.bitPerfectOutputActive) {
            outputs.firstOrNull { it.type in USB_OUTPUT_TYPES }?.let { return it }
        }
        return runCatching {
            audioManager?.getAudioDevicesForAttributes(PLAYBACK_ATTRIBUTES)
        }.getOrNull().orEmpty().firstOrNull { it.isSink }
    }

    // 设备类别：按类型归类，未归类的通路一律作为其它设备（如 HDMI、线路输出）
    private fun outputDeviceKind(type: Int): OutputDeviceKind = when {
        type in USB_OUTPUT_TYPES -> OutputDeviceKind.USB
        type in BLUETOOTH_OUTPUT_TYPES -> OutputDeviceKind.BLUETOOTH
        type in SPEAKER_OUTPUT_TYPES -> OutputDeviceKind.SPEAKER
        type in WIRED_OUTPUT_TYPES -> OutputDeviceKind.WIRED
        else -> OutputDeviceKind.OTHER
    }

    // 平台对无地址的设备以上报 0 表示；Android 13 起未授权 BLUETOOTH_CONNECT 时蓝牙地址被匿名化，
    // 两者都不是真实地址，同为「无地址」
    private fun usableAddress(raw: String): String? = raw.takeIf {
        it.isNotBlank() && it != ZERO_ADDRESS && it != ANONYMOUS_ADDRESS
    }

    private const val ZERO_ADDRESS = "0"
    private const val ANONYMOUS_ADDRESS = "02:00:00:00:00:00"
}
