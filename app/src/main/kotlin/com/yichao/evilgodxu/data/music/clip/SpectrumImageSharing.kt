package com.yichao.evilgodxu.data.music.clip

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.yichao.evilgodxu.data.music.download.sanitizeFileName
import com.yichao.evilgodxu.data.music.metadata.AlbumImageStore
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 频谱图导出：写相册或分享；中转文件留存到冷启动回收
internal object SpectrumImageSharing {

    /** 文件名兜底值 */
    private const val FALLBACK_NAME = "spectrum"

    /** 分享中转文件前缀，须与登记一致 */
    const val SHARE_FILE_PREFIX = "spectrum"

    private const val MIME_PNG = "image/png"
    private const val TAG = "SpectrumImageSharing"

    suspend fun saveToAlbum(context: Context, png: ByteArray, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            AlbumImageStore.write(context, "${safeName(fileName)}.png", MIME_PNG, png)
        }

    /** 经系统分享面板分享，返回是否拉起成功 */
    suspend fun share(
        context: Context,
        png: ByteArray,
        fileName: String,
        chooserTitle: String,
    ): Boolean {
        val name = safeName(fileName)
        val file = withContext(Dispatchers.IO) { writeShareFile(context, png, name) } ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            CrashLogManager.logException(TAG, "频谱图分享授权失败: $name", it)
            return false
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, chooserTitle)
        // 非 Activity 分享需加 NEW_TASK
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    // 落 cacheDir 根下，冷启动按前缀回收
    private fun writeShareFile(context: Context, png: ByteArray, name: String): File? = runCatching {
        File(context.cacheDir, "$SHARE_FILE_PREFIX-$name.png").apply { writeBytes(png) }
    }.onFailure {
        CrashLogManager.logException(TAG, "频谱图中转文件写入失败: $name", it)
    }.getOrNull()

    // 清洗非法字符，为空回退兜底名
    private fun safeName(fileName: String): String =
        sanitizeFileName(fileName).ifBlank { FALLBACK_NAME }
}
