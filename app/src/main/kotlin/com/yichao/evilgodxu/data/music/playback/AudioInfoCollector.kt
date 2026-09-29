package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.Player
import com.yichao.evilgodxu.data.music.analysis.isLosslessFormatName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// USB 解码器会以设备、耳机、配件三类上报，三者都是可直接播放的输出目标
private val USB_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

// 蓝牙音频输出：A2DP 承载媒体音频，SCO 为通话通路，BLE 系列为低功耗音频设备
private val BLUETOOTH_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
)

// 内置扬声器：外放通路
private val SPEAKER_OUTPUT_TYPES = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

// 设备支持的采样率最多列出前几项：设备行过长时已被展示层截断，取全量只会白占版面
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
     * 会话 ID 与传输状态，再进入 IO 采集设备信息与蓝牙设备名。
     */
    suspend fun collect(context: Context, state: MusicPlaybackState): AudioInfoSnapshot {
        val playback = playbackSnapshot(state.player)
        return withContext(Dispatchers.IO) {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val outputs = runCatching {
                audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            }.getOrNull().orEmpty().toList()
            val bluetoothAddress = outputs
                .firstOrNull { it.type in BLUETOOTH_OUTPUT_TYPES }
                ?.address
                ?.takeIf { it.isNotBlank() }
            AudioInfoSnapshot(
                sourcePath = sourcePath(state),
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
                floatOutput = state.audioSinkFloatOutput,
                latencyMs = nativeOutputLatencyMs(audioManager),
                transportState = playback.transportState,
                outputDevices = outputDevices(
                    outputs = outputs,
                    bluetoothName = bluetoothAddress
                        ?.let { BluetoothDeviceNameResolver.resolve(context, it) },
                ),
            )
        }
    }

    /**
     * 只能从播放器读取的链路项。
     *
     * MediaController 有线程归属，取值必须在其所属线程完成，故统一切到主线程读取；
     * 已在主线程时不会额外派发，避免与调用方互相等待。
     */
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
     * 输出设备：USB、蓝牙、扬声器各成一类，每类只取首个设备，避免同类重复成行。
     * 类别下无设备时不产出条目，由展示层跳过对应行。
     */
    private fun outputDevices(
        outputs: List<AudioDeviceInfo>,
        bluetoothName: String?,
    ): List<OutputDeviceInfo> = listOf(
        OutputDeviceKind.USB to USB_OUTPUT_TYPES,
        OutputDeviceKind.BLUETOOTH to BLUETOOTH_OUTPUT_TYPES,
        OutputDeviceKind.SPEAKER to SPEAKER_OUTPUT_TYPES,
    ).mapNotNull { (kind, types) ->
        val device = outputs.firstOrNull { it.type in types } ?: return@mapNotNull null
        OutputDeviceInfo(
            kind = kind,
            // 蓝牙设备名取自蓝牙服务：AudioDeviceInfo.productName 在部分设备上返回本机蓝牙名
            name = if (kind == OutputDeviceKind.BLUETOOTH) {
                bluetoothName
            } else {
                device.productName?.toString()?.takeIf { it.isNotBlank() }
            },
            address = device.address.takeIf { it.isNotBlank() && it != ZERO_ADDRESS },
            supportedSampleRates = device.sampleRates.take(MAX_LISTED_SAMPLE_RATES).toList(),
            channelCount = device.channelCounts.firstOrNull()?.takeIf { it > 0 },
        )
    }

    // 平台对无地址的设备以上报 0 表示，与空值同为「无地址」
    private const val ZERO_ADDRESS = "0"
}
