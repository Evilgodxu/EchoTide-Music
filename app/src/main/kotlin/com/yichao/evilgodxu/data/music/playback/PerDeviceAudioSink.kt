package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * 按目标设备重建的音频输出。
 *
 * 浮点输出是 [DefaultAudioSink] 的构造期开关，实例内不可更改：它决定高分辨率 PCM 源写成 32 位浮点
 * 还是先降回 16 位整型。而位完美输出流（USB 独占）只接纳与混音器属性逐字段一致的播放，
 * 设备提供哪些格式随设备而变，因此这里持有两个变体，在每次 [configure] 时按目标设备决策，
 * 决策变化即切换变体——即按设备重建输出，使该设备上能挂上的格式成为当前写出格式。
 *
 * 平台与 media3 都不对线性 PCM 做设备级能力探测，故非独占时一律保持浮点输出；
 * 决策只在 [configure] 处落地——切换需要重开 AudioTrack，只能发生在渲染器重配点。
 * 变体切换不需要回放历史配置：所有设置类调用同时下发到两个变体。
 */
@OptIn(UnstableApi::class)
class PerDeviceAudioSink(
    context: Context,
    private val audioManager: AudioManager,
    /** 独占已钉定的 USB 输出设备，null 表示当前未独占 */
    private val exclusiveTarget: () -> AudioDeviceInfo?,
    /** 输出变体变更回调：报告本次配置后是否以浮点 PCM 写出 */
    private val onOutputVariantChanged: (Boolean) -> Unit = {},
) : AudioSink {

    /** 默认变体：高分辨率源以 32 位浮点写出，保留解码精度 */
    private val floatSink: AudioSink = buildSink(context, enableFloatOutput = true)

    /** 降级变体：一律以 16 位整型写出，供位完美流只提供整型格式的设备使用 */
    private val intSink: AudioSink = buildSink(context, enableFloatOutput = false)

    private var listener: AudioSink.Listener = SILENT_LISTENER

    /** 当前生效的变体；初始按浮点输出，与无独占设备时的决策一致 */
    private var floatActive = true

    private fun buildSink(context: Context, enableFloatOutput: Boolean): AudioSink =
        // 变速/变调交给 AudioTrack 原生处理，避免 Sonic 软件变速在低速时产生噪声
        DefaultAudioSink.Builder(context)
            .setEnableAudioOutputPlaybackParameters(true)
            .setEnableFloatOutput(enableFloatOutput)
            .build()

    /** 当前生效的变体：流数据、位置查询与格式查询都只经它 */
    private fun active(): AudioSink = if (floatActive) floatSink else intSink

    /**
     * 该曲目是否需要浮点写出。
     *
     * 线性 PCM 的设备级浮点能力无从探测（AudioTrack 经混音输出普遍接受浮点，media3 也只按 API 级别
     * 判定支持），按设备分化的只有位完美流的格式匹配，因此仅在独占已钉定设备时决策。
     * 判定与独占侧共用 [selectBitPerfectMixer]：选中的条目即独占侧待下发的混音器属性，写出编码须与
     * 之逐字段一致——已核实，格式与偏好不符时 AudioFlinger 不会报错，而是把该轨静默混音输出，
     * 「已独占」名不副实，故两处必须取同一口径。
     */
    private fun requiresFloatOutput(format: Format): Boolean {
        val device = exclusiveTarget() ?: return true
        // 16 位及以下源在两种变体下都写成整型，重建不会改变挂接结果
        if (!Util.isEncodingHighResolutionPcm(format.pcmEncoding)) return true
        val bitPerfect = selectBitPerfectMixer(
            audioManager.getSupportedMixerAttributes(device),
            format.sampleRate,
            format.channelCount,
            format.pcmEncoding,
        )
        // 只有 16 位整型条目可挂接时降级为整型变体；其余（含仅 24/32 位整型条目、无条目）保持浮点
        return bitPerfect?.format?.encoding != AudioFormat.ENCODING_PCM_16BIT
    }

    private fun forEachSink(action: (AudioSink) -> Unit) {
        action(floatSink)
        action(intSink)
    }

    private fun switchTo(useFloat: Boolean) {
        if (useFloat == floatActive) return
        // 退出方先静默再复位：复位会释放其 AudioTrack 并回调监听器，
        // 此时渲染器正在重配，事件不应再透传出去
        active().let {
            it.setListener(SILENT_LISTENER)
            it.reset()
        }
        floatActive = useFloat
        active().setListener(listener)
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        switchTo(requiresFloatOutput(audioSinkConfig.format))
        onOutputVariantChanged(floatActive)
        active().configure(audioSinkConfig)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        forEachSink { it.setListener(listener) }
    }

    override fun supportsFormat(format: Format): Boolean = active().supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = active().getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        active().getFormatOffloadSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        active().getCurrentPositionUs(sourceEnded)

    override fun getAudioTrackBufferSizeUs(): Long = active().getAudioTrackBufferSizeUs()

    override fun getAudioCapabilities(): AudioCapabilities? = active().audioCapabilities

    override fun getAudioAttributes(): AudioAttributes? = active().audioAttributes

    override fun getPlaybackParameters(): PlaybackParameters = active().playbackParameters

    override fun getSkipSilenceEnabled(): Boolean = active().skipSilenceEnabled

    override fun isEnded(): Boolean = active().isEnded

    override fun hasPendingData(): Boolean = active().hasPendingData()

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = active().handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun play() = active().play()

    override fun pause() = active().pause()

    override fun flush() = active().flush()

    override fun playToEndOfStream() = active().playToEndOfStream()

    override fun handleDiscontinuity() = active().handleDiscontinuity()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        forEachSink { it.setPlaybackParameters(playbackParameters) }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        forEachSink { it.setSkipSilenceEnabled(skipSilenceEnabled) }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) =
        forEachSink { it.setAudioAttributes(audioAttributes) }

    override fun setAudioSessionId(audioSessionId: Int) =
        forEachSink { it.setAudioSessionId(audioSessionId) }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) =
        forEachSink { it.setAuxEffectInfo(auxEffectInfo) }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) =
        forEachSink { it.setPreferredDevice(audioDeviceInfo) }

    override fun setVirtualDeviceId(virtualDeviceId: Int) =
        forEachSink { it.setVirtualDeviceId(virtualDeviceId) }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) =
        forEachSink { it.setOutputStreamOffsetUs(outputStreamOffsetUs) }

    override fun setPlayerId(playerId: PlayerId?) = forEachSink { it.setPlayerId(playerId) }

    override fun setClock(clock: Clock) = forEachSink { it.setClock(clock) }

    override fun setVolume(volume: Float) = forEachSink { it.setVolume(volume) }

    override fun enableTunnelingV21() = forEachSink { it.enableTunnelingV21() }

    override fun disableTunneling() = forEachSink { it.disableTunneling() }

    override fun setOffloadMode(offloadMode: Int) = forEachSink { it.setOffloadMode(offloadMode) }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        forEachSink { it.setOffloadDelayPadding(delayInFrames, paddingInFrames) }

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) =
        forEachSink { it.setAudioOutputProvider(audioOutputProvider) }

    override fun reset() = forEachSink { it.reset() }

    override fun release() = forEachSink { it.release() }

    private companion object {
        /** 变体退出使用期间接管回调，避免复位动作的事件外泄到渲染器 */
        val SILENT_LISTENER = object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = Unit

            override fun onUnderrun(
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) = Unit

            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
        }
    }
}
