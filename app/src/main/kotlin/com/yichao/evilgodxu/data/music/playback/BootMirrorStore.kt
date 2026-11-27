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

// 启动镜像落点：首帧需同步读，故独立文件
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

/** 播放启动镜像：只服务冷启动首帧；以 DataStore 为准 */
internal class BootMirrorStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private var writeJob: Job? = null

    /** 在途写入任务，退出前须等它落盘 */
    val inFlightWrite: Job? get() = writeJob

    /** 是否有可预置的镜像 */
    fun has(context: Context): Boolean = BOOT_MIRROR_STORE.locate(context).let { it.isFile && it.length() > 0L }

    /** 同步读取镜像，缺失或损坏为 null */
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

    /** 落盘当前曲目、进度、模式与取色 */
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
