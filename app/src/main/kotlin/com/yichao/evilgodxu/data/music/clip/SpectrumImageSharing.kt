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

// 频谱图导出：把界面渲染好的 PNG 字节写入系统相册或经系统分享面板分享。
// 写入相册复用 AlbumImageStore 的 pending 协议；分享经 FileProvider 授权 cacheDir 下的中转文件，
// 该文件须留存到目标应用读取完成，故不即时删除，由冷启动按前缀回收（见 CacheInventory）
internal object SpectrumImageSharing {

    /** 文件名的兜底值：曲名不可用或清洗后为空时仍给出可辨识的产物名 */
    private const val FALLBACK_NAME = "spectrum"

    /** 分享中转文件名前缀：与 CacheInventory 登记的中转文件前缀一致 */
    const val SHARE_FILE_PREFIX = "spectrum"

    private const val MIME_PNG = "image/png"
    private const val TAG = "SpectrumImageSharing"

    /** 把导出图写入系统相册，返回是否成功 */
    suspend fun saveToAlbum(context: Context, png: ByteArray, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            AlbumImageStore.write(context, "${safeName(fileName)}.png", MIME_PNG, png)
        }

    /**
     * 经系统分享面板分享导出图，返回是否成功拉起。
     *
     * 先落到 cacheDir 下的中转文件再交 FileProvider 授权：分享目标的进程晚于本应用读取该 URI，
     * 故不能沿用「流程结束即删」的临时文件做法
     */
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
        // LocalContext 是本地化包装 context，非 Activity 时需加 NEW_TASK
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    // 中转文件落在 cacheDir 根下：前缀已登记，冷启动可整批回收
    private fun writeShareFile(context: Context, png: ByteArray, name: String): File? = runCatching {
        File(context.cacheDir, "$SHARE_FILE_PREFIX-$name.png").apply { writeBytes(png) }
    }.onFailure {
        CrashLogManager.logException(TAG, "频谱图中转文件写入失败: $name", it)
    }.getOrNull()

    // 文件名清洗：曲名可含路径分隔符等非法字符，清洗后为空则回退兜底名
    private fun safeName(fileName: String): String =
        sanitizeFileName(fileName).ifBlank { FALLBACK_NAME }
}
