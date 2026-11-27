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

/** 把 ReplayGain 标签折算成播放器音量系数 */
internal class ReplayGainVolume(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrack: () -> MusicTrack?,
    /** 系数变化后由持有方重写播放器音量 */
    private val onApplied: () -> Unit,
) {

    /** 设置项，关闭时系数为 1 */
    private var enabled = false

    /** 当前音量系数 */
    var linear by mutableFloatStateOf(1f)
        private set

    private var loadedKey: String? = null
    private var loadJob: Job? = null
    // 标签值按曲目缓存，避免重复解析
    private val cache = HashMap<String, Pair<Double?, Double?>?>()
    private var tagLinear = 1f

    fun applyEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        applyLinear()
    }

    /** 曲目落定后调用，同一首不重复读盘 */
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
