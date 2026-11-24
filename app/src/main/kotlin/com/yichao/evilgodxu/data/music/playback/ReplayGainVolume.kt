package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.metadata.TrackMetadataEditor
import com.yichao.evilgodxu.data.music.metadata.parseReplayGainDb
import com.yichao.evilgodxu.data.music.metadata.parseReplayGainPeak
import com.yichao.evilgodxu.data.music.metadata.replayGainLinear
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 音量增益：把曲目内嵌的 ReplayGain 标签折算成乘进播放器音量的线性系数。
 *
 * 取值只认本地文件里已经写好的标签，不去在线补、也不在播放时自算：标签缺失即视为本曲不施加。
 *
 * 施加点是播放器音量（[applyLinear] 由持有方接到 `MediaController.volume`），而不是各自的
 * 写出缓冲：平台路径与独占路径都会把这一层乘进输出，两条路因此不会各自实现一份增益。
 */
internal class ReplayGainVolume(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrack: () -> MusicTrack?,
    /** 系数变化后的落地：持有方据此把音量重新写进播放器 */
    private val onApplied: () -> Unit,
) {

    /** 设置项：关闭时系数恒为 1 */
    private var enabled = false

    /** 当前应乘进播放器音量的系数 */
    var linear by mutableFloatStateOf(1f)
        private set

    private var loadedKey: String? = null
    private var loadJob: Job? = null
    // 已读到的标签值按曲目缓存：切回同一首不必再解一次容器
    private val cache = HashMap<String, Pair<Double?, Double?>?>()
    private var tagLinear = 1f

    fun applyEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        applyLinear()
    }

    /** 曲目落定后调用；同一首重复调用不会重复读盘 */
    fun onTrackChanged() {
        val track = currentTrack()
        val key = track?.let { "${it.id}|${it.audioUri}" }
        if (key == loadedKey) return
        loadedKey = key
        loadJob?.cancel()
        tagLinear = 1f
        applyLinear()
        if (track == null || key == null || !track.isLocalAudioSource) return
        val appContext = context() ?: return
        loadJob = scope().launch {
            val values = cache.getOrPut(key) { readTags(appContext, track) }
            val tagGain = values?.first
            tagLinear = if (tagGain == null) 1f else replayGainLinear(tagGain, values.second).linear
            applyLinear()
        }
    }

    private suspend fun readTags(context: Context, track: MusicTrack): Pair<Double?, Double?>? {
        val tags = TrackMetadataEditor.read(context, track) ?: return null
        val gain = parseReplayGainDb(tags.fields[TagField.REPLAY_GAIN_TRACK_GAIN]) ?: return null
        return gain to parseReplayGainPeak(tags.fields[TagField.REPLAY_GAIN_TRACK_PEAK])
    }

    private fun applyLinear() {
        linear = if (enabled) tagLinear else 1f
        onApplied()
    }
}
