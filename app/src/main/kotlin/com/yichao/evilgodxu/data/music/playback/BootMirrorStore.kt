package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 播放启动镜像：单键轻量副本，只服务冷启动首帧。
 *
 * 冷启动首帧必须在主线程同步拿到上次播放的曲目、进度与背景取色；完整恢复要先读 DataStore、
 * 再解析整份播放列表 JSON 并排序，放在首帧关键路径上就是一段可见的空窗。
 * DataStore 与播放列表缓存仍是唯一事实源，异步恢复完成后再以它们为准覆盖校正。
 *
 * 写入点为「切歌与周期进度保存」与「背景取色落盘」两处，写入时同步 commit：
 * 异步落盘在进程被杀时会让镜像滞后一次启动。
 */
internal class BootMirrorStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private companion object {
        const val KEY = "playback_snapshot"
    }

    data class Snapshot(
        val track: MusicTrack,
        val position: Long,
        val modeOrdinal: Int,
        val colorUri: String?,
        val colors: CoverBackgroundColors?,
    )

    private var writeJob: Job? = null

    /**
     * 在途的镜像写入任务。
     *
     * 定时关闭的退出要等它与播放状态一并落盘后再终止进程，
     * 否则镜像会停在已播完的那一首，下次启动点击播放就重播它。
     */
    val inFlightWrite: Job? get() = writeJob

    /** 是否存在可预置的启动镜像：首启动没有，据此跳过首帧前的同步读盘。 */
    fun has(context: Context): Boolean = runCatching {
        context.getSharedPreferences(MusicPlaybackState.BOOT_MIRROR_STORE.name, Context.MODE_PRIVATE)
            .contains(KEY)
    }.getOrDefault(false)

    /** 同步读取镜像；缺失或解析失败时为空。 */
    fun read(context: Context): Snapshot? {
        val json = runCatching {
            context.getSharedPreferences(MusicPlaybackState.BOOT_MIRROR_STORE.name, Context.MODE_PRIVATE)
                .getString(KEY, null)
        }.getOrNull() ?: return null
        val snapshot = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val track = runCatching { PlaylistCodec.decode(context, snapshot.optString("track", "")) }
            .getOrNull()?.firstOrNull() ?: return null
        val colorUri = snapshot.optString("coverGradientUri", "")
        // 封面边缘三段色；历史镜像只记录代表色（coverGradientEdge），读回时按单色展开
        val colors = if (colorUri.isNotBlank()) {
            val edge = snapshot.optInt("coverGradientEdge")
            CoverBackgroundColors(
                top = Color(snapshot.optInt("coverGradientTop", edge)),
                middle = Color(edge),
                bottom = Color(snapshot.optInt("coverGradientBottom", edge)),
            )
        } else {
            null
        }
        return Snapshot(
            track = track,
            position = snapshot.optLong("position", 0L),
            modeOrdinal = snapshot.optInt("mode", -1),
            colorUri = colorUri.takeIf { it.isNotBlank() },
            colors = colors,
        )
    }

    /** 同步落盘当前曲目、进度、播放模式与背景取色，供下次冷启动首帧同步取用。 */
    fun write(
        track: MusicTrack,
        position: Long,
        modeOrdinal: Int,
        colorUri: String,
        colors: CoverBackgroundColors?,
    ) {
        val context = context() ?: return
        writeJob = scope().launch(Dispatchers.IO) {
            runCatching {
                val snapshot = JSONObject()
                    .put("track", PlaylistCodec.encode(listOf(track)))
                    .put("position", position)
                    .put("mode", modeOrdinal)
                    .put("coverGradientUri", colorUri)
                    .put("coverGradientEdge", colors?.representative?.toArgb() ?: 0)
                    .put("coverGradientTop", colors?.top?.toArgb() ?: 0)
                    .put("coverGradientBottom", colors?.bottom?.toArgb() ?: 0)
                context.getSharedPreferences(MusicPlaybackState.BOOT_MIRROR_STORE.name, Context.MODE_PRIVATE)
                    .edit(commit = true) {
                        putString(KEY, snapshot.toString())
                    }
            }.onFailure {
                CrashLogManager.logException("BootMirrorStore", "写入播放启动镜像失败", it)
            }
        }
    }
}
