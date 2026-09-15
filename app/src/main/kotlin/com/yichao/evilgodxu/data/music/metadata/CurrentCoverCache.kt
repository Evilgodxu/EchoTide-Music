package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前曲目封面落盘缓存：切歌后即把该曲目封面缩略图写入应用 cache 目录，
 * 使进程被杀后的冷启动首帧可直接读该文件出图，无需再查系统略缩图或解码音频内嵌封面。
 *
 * 只保留最后播放的一首：目录内同一时刻仅一个文件，文件名由音频 URI 推出，换歌即覆盖。
 * 该产出可由封面源重建，属可随时回收的系统缓存；封面被重写后由 [clear] 作废。
 */
internal object CurrentCoverCache {

    /** 落盘尺寸：与音乐面板、首页大封面请求的档位一致，冷启动可直接命中该尺寸 */
    const val THUMBNAIL_SIZE = 512

    private const val DIR_NAME = "current_cover"
    private const val TEMP_SUFFIX = ".tmp"
    private const val JPEG_QUALITY = 90
    private const val TAG = "CurrentCoverCache"

    // 内存镜像：落盘与冷启动预读后驻留，供首帧同步取用，避免在主线程读盘解码
    @Volatile
    private var resident: Pair<String, Bitmap>? = null

    /** 缓存落点：供缓存台账统计与回收引用，路径只在此定义一次 */
    fun location(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** 同步取已驻留的封面：仅当与请求曲目同源时返回，供 Compose 首帧直接出图 */
    fun peek(audioUri: String): Bitmap? = resident?.takeIf { it.first == audioUri }?.second

    /** 读回该曲目的落盘封面：未落盘或解码失败返回 null，命中后驻留内存镜像 */
    suspend fun load(context: Context, audioUri: String): Bitmap? {
        peek(audioUri)?.let { return it }
        return withContext(Dispatchers.IO) {
            decode(fileFor(context, audioUri))?.also { resident = audioUri to it }
        }
    }

    /**
     * 同步读回该曲目的落盘封面并驻留内存镜像。
     *
     * 仅冷启动首帧同步预置使用（见 MusicPlaybackState.seedFromBootMirror）：封面必须在首帧组合前
     * 就已就位，否则封面会先渲染占位符再出图。读盘量为一张封面缩略图，调用点只有 Application.onCreate 一处。
     */
    fun loadBlocking(context: Context, audioUri: String) {
        if (peek(audioUri) != null) return
        decode(fileFor(context, audioUri))?.let { resident = audioUri to it }
    }

    /**
     * 确保该曲目封面已落盘：已有落盘封面时直接复用，未落盘才用 [decodeSource] 取图并写入。
     * 返回可用于取色的位图；取图与写入均失败时返回 null。
     */
    suspend fun ensure(context: Context, audioUri: String, decodeSource: suspend () -> Bitmap?): Bitmap? {
        load(context, audioUri)?.let { return it }
        val bitmap = decodeSource() ?: return null
        persist(context, audioUri, bitmap)
        return bitmap
    }

    /** 作废落盘封面与内存镜像：封面被重写后旧图不再成立 */
    suspend fun clear(context: Context) {
        resident = null
        withContext(Dispatchers.IO) { runCatching { location(context).deleteRecursively() } }
    }

    // 写入：先落中转文件再改名，进程在写入中被杀不会留下半截封面
    private suspend fun persist(context: Context, audioUri: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        // 硬件位图不可直接压缩，转为软件位图；改造结果与显示端同一张图，不影响取色
        val software = bitmap.takeIf { it.config != Bitmap.Config.HARDWARE }
            ?: bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: return@withContext
        runCatching {
            val dir = location(context)
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
            val target = fileFor(context, audioUri)
            val temp = File(dir, target.name + TEMP_SUFFIX)
            temp.outputStream().use { software.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!temp.renameTo(target)) {
                temp.delete()
                return@runCatching
            }
            // 只保留最后播放的一首：清掉换歌前的旧封面，避免目录内堆积多份且归属含糊
            dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
            resident = audioUri to software
        }.onFailure {
            CrashLogManager.logException(TAG, "落盘当前曲目封面失败: $audioUri", it)
        }
    }

    // 文件名由音频 URI 推出：换歌即写到新名字，冷启动按同一推导取回，不会读到别首曲目的封面
    private fun fileFor(context: Context, audioUri: String): File =
        File(location(context), "cover_${audioUri.hashCode().toUInt().toString(16)}.jpg")

    private fun decode(file: File): Bitmap? = runCatching {
        if (!file.isFile) return@runCatching null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()
}