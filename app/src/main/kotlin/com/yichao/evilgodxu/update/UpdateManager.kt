package com.yichao.evilgodxu.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.net.toUri
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import com.yichao.evilgodxu.data.persistence.StoragePaths
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import okhttp3.Request

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val changelog: String,
    // 期望 SHA-256 小写十六进制；空表示不可校验
    val sha256: String = "",
    val isDownloading: Boolean = false,
    val downloadId: Long? = null
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data object Success : DownloadState()
    data class Failed(val errorMessage: String) : DownloadState()
}

object UpdateManager {

    private const val TAG = "UpdateManager"

    private const val GITHUB_OWNER = "Evilgodxu"
    private const val GITHUB_REPO = "EchoTide-Music"
    const val GITHUB_REPOSITORY_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    private val json = Json { ignoreUnknownKeys = true }

    // 仅声明所用字段，其余忽略
    @Serializable
    private data class GitHubRelease(
        val tag_name: String = "",
        val body: String = "",
        val assets: List<GitHubAsset> = emptyList()
    )

    @Serializable
    private data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = "",
        // sha256:<hex>，可能为空
        val digest: String = ""
    )

    fun currentDay(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    suspend fun checkForUpdate(
        store: UpdateStore,
        context: Context,
        force: Boolean = false,
        onError: ((Exception) -> Unit)? = null
    ): UpdateInfo? {
        val day = currentDay()
        // 非强制检查每天仅一次
        if (!force && !store.shouldCheckUpdate(day)) {
            return null
        }

        return try {
            val release = withContext(Dispatchers.IO) {
                val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
                json.decodeFromString<GitHubRelease>(readJson(url))
            }

            val latest = normalizeVersion(release.tag_name)
            val current = getCurrentVersion(context)
            val ignored = store.ignoredVersion()?.let(::normalizeVersion)
            val offerUpdate = isNewerVersion(latest, current) && latest != ignored

            // 顺带清理旧安装包，只保留本次可安装的那份
            withContext(Dispatchers.IO) {
                pruneOutdatedPackages(context, keepVersion = if (offerUpdate) latest else null)
            }

            if (offerUpdate) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: throw IllegalStateException("GitHub Release 未提供可用 APK")
                val downloadUrl = apkAsset.browser_download_url.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("GitHub Release 未提供可用 APK")
                // 哈希取自 digest，缺失留空，下载时拒绝安装
                val sha256 = apkAsset.digest.substringAfter("sha256:", "").trim().lowercase()

                // 一次写入检查日与待更新，避免中间态
                store.recordCheck(
                    today = day,
                    pending = PendingUpdate(
                        version = latest,
                        url = downloadUrl,
                        changelog = release.body,
                        sha256 = sha256,
                    ),
                )

                UpdateInfo(
                    latestVersion = latest,
                    downloadUrl = downloadUrl,
                    changelog = release.body,
                    sha256 = sha256
                )
            } else {
                store.recordCheck(today = day, pending = null)
                null
            }
        } catch (e: Exception) {
            CrashLogManager.logException("UpdateManager", "检查更新失败", e)
            onError?.invoke(e)
            null
        }
    }

    // 文件名由版本号决定，下载与清理共用
    private fun apkFileName(version: String): String = "EchoTideMusic_$version.apk"

    // 清理非当前版本的安装包；null 时全清
    private fun pruneOutdatedPackages(context: Context, keepVersion: String?) {
        val dir = java.io.File(context.getExternalFilesDir(null), StoragePaths.UPDATE_DIR_NAME)
        val keepName = keepVersion?.let { apkFileName(it) }
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.filterNot { it.name == keepName }
            ?.forEach { it.delete() }
    }

    private fun readJson(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "EchoTideMusic/$GITHUB_REPO")
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val text = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("更新服务响应异常: HTTP ${resp.code}")
            text
        }
    }

    internal fun normalizeVersion(version: String): String {
        return version.trim().trimStart('v', 'V')
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "0.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            CrashLogManager.logException("UpdateManager", "获取当前版本号失败", e)
            "0.0.0"
        }
    }

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** 下载并引导安装；已有同名包先校验；15 秒无进度判失败 */
    suspend fun downloadAndInstall(
        store: UpdateStore,
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val fileName = apkFileName(updateInfo.latestVersion)
        // 放自定义 update 子目录，避免留空目录
        val outFile = java.io.File(
            java.io.File(context.getExternalFilesDir(null), StoragePaths.UPDATE_DIR_NAME),
            fileName
        )

        // 全部在 IO 线程，避免 IPC 阻塞主线程
        return withContext(Dispatchers.IO) {
            // 真下载时才建目录
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) {
                if (verifyApkHash(outFile, updateInfo.sha256)) {
                    onProgress(1f)
                    installApk(store, context, outFile)
                    return@withContext true
                }
                outFile.delete()
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(requireHttps(updateInfo.downloadUrl).toUri())
                .setTitle(context.getString(R.string.update_notification_title))
                .setDescription(context.getString(R.string.update_notification_downloading, updateInfo.latestVersion))
                .setDestinationUri(Uri.fromFile(outFile))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadId = dm.enqueue(req)
            var lastProgressBytes = -1L
            var stallCount = 0
            val STALL_TIMEOUT = 30  // 30 次无进度 * 500ms = 15 秒

            while (true) {
                val (status, done, total) = dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    Triple(
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    )
                } ?: break

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onProgress(1f)
                        // 与 SHA-256 比对，不符即拒绝安装
                        if (!verifyApkHash(outFile, updateInfo.sha256)) {
                            outFile.delete()
                            onProgress(-1f)
                            return@withContext false
                        }
                        installApk(store, context, outFile)
                        return@withContext true
                    }
                    DownloadManager.STATUS_FAILED -> {
                        onProgress(-1f)
                        return@withContext false
                    }
                    else -> {
                        if (total > 0) {
                            onProgress(done.toFloat() / total)
                        }

                        if (done == lastProgressBytes) {
                            stallCount++
                            if (stallCount >= STALL_TIMEOUT) {
                                dm.remove(downloadId)
                                onProgress(-1f)
                                return@withContext false
                            }
                        } else {
                            lastProgressBytes = done
                            stallCount = 0
                        }
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
            onProgress(-1f)
            false
        }
    }

    // 安装后清掉待更新，避免重复提示
    private suspend fun installApk(store: UpdateStore, context: Context, apkFile: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        clearPendingUpdate(store)
    }

    // 校验 SHA-256；缺失或不符判失败
    private fun verifyApkHash(file: java.io.File, expected: String): Boolean {
        if (expected.isBlank()) {
            CrashLogManager.logException(TAG, "APK 缺少期望哈希，拒绝安装: ${file.name}")
            return false
        }
        val actual = runCatching { sha256Hex(file) }.getOrElse { e ->
            CrashLogManager.logException(TAG, "计算 APK 哈希失败: ${file.name}", e)
            return false
        }
        if (!actual.equals(expected, ignoreCase = true)) {
            CrashLogManager.logException(TAG, "APK 哈希不匹配（期望 $expected，实际 $actual），拒绝安装")
            return false
        }
        return true
    }

    // 流式算 SHA-256，不整包读入
    private fun sha256Hex(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // 仅允许 HTTPS 地址
    private fun requireHttps(url: String): String {
        if (!url.startsWith("https://")) {
            throw IllegalArgumentException("不安全的下载地址: $url")
        }
        return url
    }

    suspend fun clearPendingUpdate(store: UpdateStore) {
        store.clearPending()
    }

    /** 读取待更新信息；同一天重开不再自动检查 */
    suspend fun readPendingUpdate(store: UpdateStore): UpdateInfo? {
        val pending = store.readPending() ?: return null
        return UpdateInfo(
            latestVersion = pending.version,
            downloadUrl = pending.url,
            changelog = pending.changelog,
            sha256 = pending.sha256,
        )
    }

    suspend fun ignoreVersion(store: UpdateStore, version: String) {
        store.ignore(version)
    }

    // 逐段按整数比较，避免 1.10 < 1.9
    internal fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
