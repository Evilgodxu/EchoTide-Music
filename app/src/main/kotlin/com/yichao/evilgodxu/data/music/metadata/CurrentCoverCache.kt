package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 当前曲目封面落盘缓存，只保留最后播放的一首 */
internal object CurrentCoverCache {

    /** 落盘尺寸取系统略缩图上限档 */
    const val THUMBNAIL_SIZE = MusicCoverLoader.SYSTEM_THUMBNAIL_MAX_SIZE_PX

    private const val DIR_NAME = "current_cover"
    private const val TEMP_SUFFIX = ".tmp"
    private const val JPEG_QUALITY = 90
    private const val TAG = "CurrentCoverCache"

    // 内存镜像，供首帧同步取用
    @Volatile
    private var resident: Pair<String, Bitmap>? = null

    // 作废代数，挡住作废后回填的旧图
    @Volatile
    private var generation = 0

    /** 缓存落点，供台账统计与回收 */
    fun location(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** 同步取已驻留封面，仅同源时返回 */
    fun peek(audioUri: String): Bitmap? = resident?.takeIf { it.first == audioUri }?.second

    /** 读回落盘封面，命中后驻留内存 */
    suspend fun load(context: Context, audioUri: String): Bitmap? {
        peek(audioUri)?.let { return it }
        val startGeneration = generation
        return withContext(Dispatchers.IO) {
            // 读取期间被作废则丢弃，由调用方回退
            decode(fileFor(context, audioUri))
                ?.takeIf { startGeneration == generation }
                ?.also { resident = audioUri to it }
        }
    }

    /** 冷启动首帧同步预置，仅 onCreate 调用 */
    fun loadBlocking(context: Context, audioUri: String) {
        if (peek(audioUri) != null) return
        decode(fileFor(context, audioUri))?.let { resident = audioUri to it }
    }

    /** 确保封面已落盘并返回位图，失败 null */
    suspend fun ensure(context: Context, audioUri: String, decodeSource: suspend () -> Bitmap?): Bitmap? {
        load(context, audioUri)?.let { return it }
        val bitmap = decodeSource() ?: return null
        persist(context, audioUri, bitmap)
        return bitmap
    }

    /** 作废落盘与内存镜像 */
    suspend fun clear(context: Context) {
        generation++
        resident = null
        withContext(Dispatchers.IO) { runCatching { location(context).deleteRecursively() } }
    }

    /** 回收未改名的 .tmp 残留 */
    fun reclaimTempResidue(context: Context, olderThanMs: Long) {
        reclaimTempResidueIn(location(context), TEMP_SUFFIX, olderThanMs)
    }

    // 先写中转再改名，避免半截封面
    private suspend fun persist(context: Context, audioUri: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        // 硬件位图不可压缩，转软件位图
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
            // 只保留最后一首，清掉旧封面
            dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
            resident = audioUri to software
        }.onFailure {
            CrashLogManager.logException(TAG, "落盘当前曲目封面失败: $audioUri", it)
        }
    }

    // 文件名由音频 URI 推导，冷启动可取回
    private fun fileFor(context: Context, audioUri: String): File =
        File(location(context), "cover_${audioUri.hashCode().toUInt().toString(16)}.jpg")

    private fun decode(file: File): Bitmap? = runCatching {
        if (!file.isFile) return@runCatching null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()
}