package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放链路快照：源格式到 USB 成色
 * 只服务信息面板与路由判定。
 */
internal class AudioSignalPathState(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrack: () -> MusicTrack?,
) {

    var audioSignalPathFormat by mutableStateOf<AudioSignalPathFormat?>(null)
    // 格式信息所属曲目
    var audioSignalPathTrackId by mutableStateOf<Long?>(null)
    // 格式所属音源 URI；换源时 ID 不变，须一并判定
    var audioSignalPathSourceUri by mutableStateOf<String?>(null)

    // 平台解码器名，初始化回调写入；可能残留，须校验归属
    var audioDecoderName by mutableStateOf<String?>(null)

    // 音频轨编码 mime，与是否被选中无关；停止后保持末值
    var audioTrackMimeType by mutableStateOf<String?>(null)
    // 是否选中，无解码器的唯一判据
    var audioTrackDecodable by mutableStateOf<Boolean?>(null)
    // 是否以浮点 PCM 写出，null 为输出未建立
    var audioSinkFloatOutput by mutableStateOf<Boolean?>(null)

    // 实际写出的 PCM 编码，null 为输出未建立
    var audioSinkOutputEncoding by mutableStateOf<Int?>(null)

    /** 音频轨实际采样率，null 为未建立；倍率档须取轨道自报值 */
    var audioSinkOutputSampleRate by mutableStateOf<Int?>(null)

    /** 解码头实际输出格式，null 为未取得；重配时上报 */
    var audioSinkDecodedFormat by mutableStateOf<DecodedOutputFormat?>(null)

    /** 当前音频轨，null 未建立；采集线程读故 volatile */
    @Volatile
    var audioTrack: AudioTrack? = null

    /** 已写入帧数入口；外层 null 无，内层 null 取不到 */
    var audioSinkWrittenFrames: (() -> Long?)? = null

    /** 独占路径延迟读数入口；未建立等时输出时为 null */
    var usbExclusiveLatency: (() -> OutputLatencyReading?)? = null
    // 直出成色，未启用或无可用档位时为系统混音
    var directOutputMode by mutableStateOf(AudioOutputMode.MIXER)

    /** 独占路径是否在承接写出；判定方与直出成色不同，故独立记 */
    var usbExclusiveRoute by mutableStateOf(false)

    /** 独占接管到的设备，未接管为 null；设备已离开系统音频 */
    var usbExclusiveDevice by mutableStateOf<UsbExclusiveDeviceInfo?>(null)

    /** 独占是否已接管设备；音量归属以此为准，不等路由成立 */
    var usbExclusiveClaimed by mutableStateOf(false)

    /** 独占音量读写入口，由播放服务装配；会话未连上也可用 */
    var exclusiveVolumeStep: (() -> Int)? = null
    var setExclusiveVolumeStep: ((Int) -> Unit)? = null

    /** 两个 USB 输出开关状态；心动准入看开关而非成色 */
    var usbDirectOutputEnabled by mutableStateOf(false)
    var usbExclusiveEnabled by mutableStateOf(false)

    /** 格式信息是否对应当前曲目与音源 */
    val isCurrentForCurrentTrack: Boolean
        get() = currentTrack()?.let(::isSourceCurrent) == true

    /** 曲目 id 到源格式：按 id 取位深；三线程读写 */
    private val knownSourceFormats = ConcurrentHashMap<Long, SourceFormat>()

    /** 容器头里的位深与声道 */
    private data class SourceFormat(val bitDepth: Int?, val channels: Int?)

    /** 按 id 取已知源位深；未预读到为 null */
    fun bitDepthOfTrack(trackId: Long): Int? = knownSourceFormats[trackId]?.bitDepth

    /** 预读某曲目容器头入档；只读位深，已入档即跳过 */
    fun preloadSourceFormat(track: MusicTrack) {
        if (knownSourceFormats.containsKey(track.id)) return
        val context = context() ?: return
        scope().launch(Dispatchers.IO) {
            val container = TrackAudioInfoReader.readContainerFormat(context, track) ?: return@launch
            rememberSourceFormat(track.id, container.bitDepth, container.channels)
        }
    }

    // 只在读到位深时入档，避免挡住后续补齐
    private fun rememberSourceFormat(trackId: Long?, bitDepth: Int?, channels: Int?) {
        if (trackId == null || bitDepth == null || bitDepth <= 0) return
        knownSourceFormats[trackId] = SourceFormat(bitDepth, channels)
    }

    /** 起播前落定源位深与声道，供解码前展示；已归属则跳过 */
    fun recordSourceBitDepthBeforePlayback(track: MusicTrack) {
        val context = context() ?: return
        if (isSourceCurrent(track)) return
        // 已预读过就沿用，不再读同一容器头
        val container = knownSourceFormats[track.id] ?: run {
            val read = TrackAudioInfoReader.readContainerFormat(context, track) ?: return
            rememberSourceFormat(track.id, read.bitDepth, read.channels)
            SourceFormat(read.bitDepth, read.channels)
        }
        audioSignalPathFormat = AudioSignalPathFormat(
            format = null,
            sampleRate = null,
            outputRate = null,
            bitDepth = container.bitDepth,
            channels = container.channels,
            bitrate = null,
        )
        audioSignalPathTrackId = track.id
        audioSignalPathSourceUri = track.audioUri
    }

    // 未播放时预读当前曲目格式，供信息条展示
    fun refreshIdleTrackFormatInfo(context: Context) {
        val track = currentTrack() ?: return
        if (isCompleteFor(track)) return
        scope().launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            // 读盘期间已切歌或已补全则不覆盖
            if (currentTrack()?.id != track.id || isCompleteFor(track)) return@launch
            rememberSourceFormat(track.id, info.bitDepth, info.channels)
            audioSignalPathFormat = info
            audioSignalPathTrackId = track.id
            audioSignalPathSourceUri = track.audioUri
        }
    }

    // 缓存完成后以本地文件补齐格式信息
    fun refreshTrackFormatInfoFromLocal(context: Context) {
        val track = currentTrack() ?: return
        if (!track.isLocalAudioSource) return
        scope().launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            if (currentTrack()?.id == track.id) {
                rememberSourceFormat(track.id, info.bitDepth, info.channels)
                audioSignalPathFormat = info
                audioSignalPathTrackId = track.id
                audioSignalPathSourceUri = track.audioUri
            }
        }
    }

    // 回前台校正音频信息，错配则重读
    fun reconcileTrackFormatInfo(context: Context) {
        val track = currentTrack() ?: return
        if (isSourceCurrent(track)) return
        audioSignalPathFormat = null
        audioSignalPathTrackId = null
        audioSignalPathSourceUri = null
        refreshIdleTrackFormatInfo(context)
    }

    // 格式信息是否属于当前曲目与音源
    private fun isSourceCurrent(track: MusicTrack): Boolean =
        audioSignalPathTrackId == track.id && audioSignalPathSourceUri == track.audioUri

    // 可用记录须归属正确且格式齐全；仅预置位深时不可当最新
    private fun isCompleteFor(track: MusicTrack): Boolean =
        isSourceCurrent(track) &&
            audioSignalPathFormat?.let { it.format != null && it.sampleRate != null } == true
}
