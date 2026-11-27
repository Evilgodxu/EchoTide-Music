package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.analysis.isLosslessFormatName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 蓝牙输出：A2DP、SCO、BLE 与助听器
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

// 有线通路：耳机与耳麦
private val WIRED_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
)

// 媒体属性：USAGE_MEDIA + MUSIC
private val PLAYBACK_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/** 当前播放链路信息采集：读不到的值留空，不用默认值顶替 */
internal object AudioInfoCollector {

    /** 采集快照：播放器项须主线程取，设备项放 IO */
    suspend fun collect(context: Context, state: MusicPlaybackState): AudioInfoSnapshot {
        val playback = playbackSnapshot(state.player)
        return withContext(Dispatchers.IO) {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val outputs = runCatching {
                audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            }.getOrNull().orEmpty().toList()
            // 先定出输出设备，设备侧速率按路由取
            val device = currentOutputDevice(context, audioManager, state, outputs)
            AudioInfoSnapshot(
                sourcePath = sourcePath(state),
                fileSizeBytes = fileSizeBytes(context, state),
                format = state.audioSignalPathFormat
                    .takeIf { state.isAudioSignalPathCurrent }
                    ?.format
                    ?.removePrefix("audio/")
                    ?.takeIf { it.isNotBlank() },
                decoder = state.audioDecoderName?.takeIf { it.isNotBlank() },
                decoderUnavailableFormat = state.decoderUnavailableFormat(),
                sourceSampleRate = state.sourceSampleRate(),
                outputSampleRate = outputSampleRate(state, audioManager, device),
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
                outputMode = state.outputMode(),
                decodedOutput = decodedOutput(state),
                audioSessionId = playback.audioSessionId,
                floatOutput = floatOutputState(state),
                outputEncoding = outputEncoding(state),
                transportState = playback.transportState,
                outputDevice = device,
            )
        }
    }

    /** 只能从播放器读的项，统一切主线程取 */
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

    // 音频路径：本地路径优先，在线回退 URI
    private fun sourcePath(state: MusicPlaybackState): String? {
        val track = state.currentTrack ?: return null
        return track.path.takeIf { it.isNotBlank() }
            ?: track.audioUri.takeIf { it.isNotBlank() }
    }

    // 源文件字节数，读不到留空
    private fun fileSizeBytes(context: Context, state: MusicPlaybackState): Long? =
        state.currentTrack
            ?.let { TrackAudioInfoReader.readFileSize(context, it) }
            ?.takeIf { it > 0 }

    // 源采样率取自解码格式
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

    // 无解码器时的格式名，仅在被判定不可解码时给出
    private fun MusicPlaybackState.decoderUnavailableFormat(): String? {
        if (audioTrackDecodable != false) return null
        return audioTrackMimeType
            ?.takeIf { it.isNotBlank() }
            ?.let { TrackAudioInfoReader.mimeToFormatName(it) ?: it }
    }

    /** 生效的输出模式：独占优先，否则取直出成色 */
    private fun MusicPlaybackState.effectiveOutputMode(): AudioOutputMode =
        if (usbExclusiveRoute) AudioOutputMode.EXCLUSIVE else directOutputMode

    // 有独占用独占，否则取直出成色
    private fun MusicPlaybackState.outputMode(): AudioOutputMode? =
        if (currentTrack == null) null else effectiveOutputMode()

    /** 解码输出格式，取解码头上报值；非 PCM 无位深 */
    private fun decodedOutput(state: MusicPlaybackState): DecodedOutputFormat? {
        if (state.currentTrack == null) return null
        val format = state.audioSinkDecodedFormat ?: return null
        return format.takeIf { outputEncodingOf(it.pcmEncoding) != null }
    }

    /** 浮点写出状态；未装载不展示 */
    private fun floatOutputState(state: MusicPlaybackState): FloatOutputState? {
        if (state.currentTrack == null) return null
        return when (state.audioSinkFloatOutput) {
            true -> FloatOutputState.ENABLED
            false -> FloatOutputState.DISABLED
            null -> FloatOutputState.NOT_ESTABLISHED
        }
    }

    /** 输出编码取音频轨实际写出值；非 PCM 无位深 */
    private fun outputEncoding(state: MusicPlaybackState): OutputEncoding? {
        if (state.currentTrack == null) return null
        val raw = state.audioSinkOutputEncoding ?: return OutputEncoding.NOT_ESTABLISHED
        return outputEncodingOf(raw)
    }

    /** 输出侧实际速率：直出取轨道自报，独占取源速率 */
    private fun outputSampleRate(
        state: MusicPlaybackState,
        audioManager: AudioManager?,
        device: OutputDeviceInfo?,
    ): Int? = when (state.effectiveOutputMode()) {
        AudioOutputMode.MIXER -> mixerOutputSampleRateHz(device, platformOutputSampleRate(audioManager))
        AudioOutputMode.FORMAT_LOCKED -> state.audioSinkOutputSampleRate
        AudioOutputMode.EXCLUSIVE -> state.sourceSampleRate()
    }

    // 主输出 HAL 速率，读不到按未知
    private fun platformOutputSampleRate(audioManager: AudioManager?): Int? =
        runCatching { audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }
            .getOrNull()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    // 传输状态：缓冲/结束优先，空闲说明输出未建立
    private fun transportState(player: Player?): AudioTransportState? = when {
        player == null -> null
        player.playbackState == Player.STATE_BUFFERING -> AudioTransportState.BUFFERING
        player.playbackState == Player.STATE_ENDED -> AudioTransportState.ENDED
        player.playbackState == Player.STATE_IDLE -> AudioTransportState.IDLE
        player.playWhenReady -> AudioTransportState.PLAYING
        else -> AudioTransportState.PAUSED
    }

    /** 当前输出设备；蓝牙名称与编解码参数取自蓝牙栈 */
    private suspend fun currentOutputDevice(
        context: Context,
        audioManager: AudioManager?,
        state: MusicPlaybackState,
        outputs: List<AudioDeviceInfo>,
    ): OutputDeviceInfo? {
        // 独占期间设备已离开系统音频，由接管方给出
        state.usbExclusiveDevice?.takeIf { state.usbExclusiveRoute }?.let { device ->
            return OutputDeviceInfo(
                kind = OutputDeviceKind.USB,
                name = device.name,
                address = null,
                supportedSampleRates = device.supportedSampleRates,
                // 独占期间档位来自设备描述符与时钟查询
                sampleRateSource = SampleRateSource.DEVICE,
                channelCount = device.channelCount,
                supportedEncodings = device.supportedEncodings,
                bluetooth = null,
            )
        }
        val device = routedOutputDevice(audioManager, state, outputs) ?: return null
        val kind = outputDeviceKind(device.type)
        val address = usableAddress(device.address)
        val bluetooth = if (kind == OutputDeviceKind.BLUETOOTH) {
            BluetoothDeviceResolver.resolve(context, address)
        } else {
            null
        }
        val mixerAttributes = supportedMixerAttributes(audioManager, device)
        return OutputDeviceInfo(
            kind = kind,
            name = if (kind == OutputDeviceKind.BLUETOOTH) {
                bluetooth?.name
            } else {
                device.productName?.toString()?.takeIf { it.isNotBlank() }
            },
            address = address,
            supportedSampleRates = supportedSampleRates(device, mixerAttributes),
            sampleRateSource = SampleRateSource.PLATFORM,
            channelCount = device.channelCounts.firstOrNull()?.takeIf { it > 0 },
            supportedEncodings = mixerAttributes.mapNotNull { outputEncodingOf(it.format.encoding) }.distinct(),
            bluetooth = bluetooth?.let {
                BluetoothLinkInfo(linkType = it.linkType, deviceClass = it.deviceClass, codec = it.codec)
            },
        )
    }

    /** 设备动态混音端口条目，读不到按空 */
    private fun supportedMixerAttributes(
        audioManager: AudioManager?,
        device: AudioDeviceInfo,
    ): List<AudioMixerAttributes> =
        runCatching { audioManager?.getSupportedMixerAttributes(device) }.getOrNull().orEmpty()

    /** 设备支持采样率，取自报与混音端口声明的并集 */
    private fun supportedSampleRates(
        device: AudioDeviceInfo,
        mixerAttributes: List<AudioMixerAttributes>,
    ): List<Int> =
        (device.sampleRates.toList() + mixerAttributes.map { it.format.sampleRate })
            .filter { it > 0 }
            .distinct()
            .sorted()

    /** 系统策略选出的输出设备；直出时按 USB 类型取 */
    private fun routedOutputDevice(
        audioManager: AudioManager?,
        state: MusicPlaybackState,
        outputs: List<AudioDeviceInfo>,
    ): AudioDeviceInfo? {
        if (state.effectiveOutputMode() != AudioOutputMode.MIXER) {
            outputs.firstOrNull { it.type in USB_OUTPUT_TYPES }?.let { return it }
        }
        return runCatching {
            audioManager?.getAudioDevicesForAttributes(PLAYBACK_ATTRIBUTES)
        }.getOrNull().orEmpty().firstOrNull { it.isSink }
    }

    // 设备类别，未归类归入其它
    private fun outputDeviceKind(type: Int): OutputDeviceKind = when {
        type in USB_OUTPUT_TYPES -> OutputDeviceKind.USB
        type in BLUETOOTH_OUTPUT_TYPES -> OutputDeviceKind.BLUETOOTH
        type in SPEAKER_OUTPUT_TYPES -> OutputDeviceKind.SPEAKER
        type in WIRED_OUTPUT_TYPES -> OutputDeviceKind.WIRED
        else -> OutputDeviceKind.OTHER
    }

    // 0 与匿名地址都不算真实地址
    private fun usableAddress(raw: String): String? = raw.takeIf {
        it.isNotBlank() && it != ZERO_ADDRESS && it != ANONYMOUS_ADDRESS
    }

    private const val ZERO_ADDRESS = "0"
    private const val ANONYMOUS_ADDRESS = "02:00:00:00:00:00"
}
