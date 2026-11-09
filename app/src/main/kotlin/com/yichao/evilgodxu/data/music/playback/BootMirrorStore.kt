package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.persistenceJson
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 启动镜像的落盘落点：冷启动首帧要同步读取，故为独立文件而非 DataStore
internal val BOOT_MIRROR_STORE = PersistedData("boot_mirror.json", PersistedFileKind.APP_FILE)

@Serializable
private data class StoredColors(
    val top: Int,
    val middle: Int,
    val bottom: Int,
)

@Serializable
private data class BootSnapshot(
    val track: StoredTrack,
    val position: Long = 0L,
    val modeOrdinal: Int = -1,
    val colorUri: String? = null,
    val colors: StoredColors? = null,
)

/**
 * 播放启动镜像：只服务冷启动首帧的同步读取。
 *
 * DataStore 是唯一事实源，本文件只是它的**首帧缓存**：完整恢复要先读 DataStore、
 * 再解析整份播放列表 JSON 并排序，放在首帧关键路径上就是一段可见的空窗。
 * 镜像不承担任何语义，异步恢复完成后再以 DataStore 为准覆盖校正。
 *
 * 写入点为「切歌与周期进度保存」与「背景取色落盘」两处；写盘走临时文件改名，
 * 避免进程在写入中被杀留下半截 JSON。
 */
internal class BootMirrorStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private var writeJob: Job? = null

    /**
     * 在途的镜像写入任务。
     *
     * 定时关闭的退出要等它与播放状态一并落盘后再终止进程，
     * 否则镜像会停在已播完的那一首，下次启动点击播放就重播它。
     */
    val inFlightWrite: Job? get() = writeJob

    /** 是否存在可预置的启动镜像：首启动没有，据此跳过首帧前的同步读盘。 */
    fun has(context: Context): Boolean = BOOT_MIRROR_STORE.locate(context).let { it.isFile && it.length() > 0L }

    /** 同步读取镜像；缺失或解析失败时为空。 */
    fun read(context: Context): Snapshot? {
        val file = BOOT_MIRROR_STORE.locate(context)
        if (!file.isFile) return null
        val stored = runCatching {
            persistenceJson.decodeFromString<BootSnapshot>(file.readText())
        }.getOrElse {
            CrashLogManager.logException("BootMirrorStore", "读取播放启动镜像失败", it)
            return null
        }
        val track = runCatching { stored.track.toMusicTrack(context) }.getOrNull() ?: return null
        return Snapshot(
            track = track,
            position = stored.position,
            modeOrdinal = stored.modeOrdinal,
            colorUri = stored.colorUri,
            colors = stored.colors?.toCoverBackgroundColors(),
        )
    }

    data class Snapshot(
        val track: MusicTrack,
        val position: Long,
        val modeOrdinal: Int,
        val colorUri: String?,
        val colors: CoverBackgroundColors?,
    )

    /** 落盘当前曲目、进度、播放模式与背景取色，供下次冷启动首帧同步取用。 */
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
                val snapshot = BootSnapshot(
                    track = track.toStoredTrack(),
                    position = position,
                    modeOrdinal = modeOrdinal,
                    colorUri = colorUri,
                    colors = colors?.toStoredColors(),
                )
                val file = BOOT_MIRROR_STORE.locate(context)
                val temp = File(file.parentFile, "${BOOT_MIRROR_STORE.fileName}.tmp")
                temp.writeText(persistenceJson.encodeToString(BootSnapshot.serializer(), snapshot))
                if (!temp.renameTo(file)) {
                    temp.delete()
                    file.writeText(persistenceJson.encodeToString(BootSnapshot.serializer(), snapshot))
                }
            }.onFailure {
                CrashLogManager.logException("BootMirrorStore", "写入播放启动镜像失败", it)
            }
        }
    }
}

private fun CoverBackgroundColors.toStoredColors(): StoredColors = StoredColors(
    top = top.toArgb(),
    middle = middle.toArgb(),
    bottom = bottom.toArgb(),
)

private fun StoredColors.toCoverBackgroundColors(): CoverBackgroundColors = CoverBackgroundColors(
    top = Color(top),
    middle = Color(middle),
    bottom = Color(bottom),
)
