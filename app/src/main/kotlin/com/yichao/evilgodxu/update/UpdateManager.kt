package com.yichao.evilgodxu.update

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import androidx.core.content.edit
import androidx.core.net.toUri
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.log.CrashLogManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import okhttp3.Request

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val changelog: String,
    // APK 期望哈希：GitHub API 提供的 asset digest，已归一为小写十六进制；空表示不可校验
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

    // 待更新信息的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
    internal val PERSISTED = PersistedData("update_prefs", PersistedFileKind.SHARED_PREFS)
    private const val KEY_LAST_CHECK_DAY = "last_check_day"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_PENDING_URL = "pending_url"
    private const val KEY_PENDING_CHANGELOG = "pending_changelog"
    private const val KEY_IGNORED_VERSION = "ignored_version"
    private const val TAG = "UpdateManager"

    private const val GITHUB_OWNER = "Evilgodxu"
    private const val GITHUB_REPO = "EchoTide-Music"
    const val GITHUB_REPOSITORY_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    private val json = Json { ignoreUnknownKeys = true }
    private val prefsMap = ConcurrentHashMap<String, android.content.SharedPreferences>()

    private fun prefs(context: Context): android.content.SharedPreferences {
        return prefsMap.getOrPut(context.packageName) {
            context.getSharedPreferences(PERSISTED.name, Context.MODE_PRIVATE)
        }
    }

    // 仅声明用到的字段，其余由 ignoreUnknownKeys 忽略
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
        // GitHub API 自 2025-06 起为 release asset 提供 "sha256:<hex>" 摘要；缺失或旧资源可能为空
        val digest: String = ""
    )

    fun shouldCheckUpdate(context: Context): Boolean {
        val lastCheckDay = prefs(context).getString(KEY_LAST_CHECK_DAY, null)
        return lastCheckDay != currentDay()
    }

    private fun currentDay(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    suspend fun checkForUpdate(
        context: Context,
        force: Boolean = false,
        onError: ((Exception) -> Unit)? = null
    ): UpdateInfo? {
        val prefs = prefs(context)

        // 非强制检查仅当进入新的一天时才执行
        if (!force && !shouldCheckUpdate(context)) {
            return null
        }

        return try {
            val day = currentDay()
            val release = withContext(Dispatchers.IO) {
                val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
                json.decodeFromString<GitHubRelease>(readJson(url))
            }

            val latest = normalizeVersion(release.tag_name)
            val current = getCurrentVersion(context)
            val ignored = prefs.getString(KEY_IGNORED_VERSION, null)?.let(::normalizeVersion)

            if (isNewerVersion(latest, current) && latest != ignored) {
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: throw IllegalStateException("GitHub Release 未提供可用 APK")
                val downloadUrl = apkAsset.browser_download_url.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("GitHub Release 未提供可用 APK")
                // 期望哈希取自 asset digest（形如 "sha256:<hex>"），归一为小写十六进制；
                // 缺失时留空，下载阶段会因无法校验而拒绝安装
                val sha256 = apkAsset.digest.substringAfter("sha256:", "").trim().lowercase()

                // 同步写盘：待更新信息用于冷启动恢复，异步落盘存在进程被杀丢失窗口
                withContext(Dispatchers.IO) {
                    prefs.edit(commit = true) {
                        putString(KEY_LAST_CHECK_DAY, day)
                        putString(KEY_PENDING_VERSION, latest)
                        putString(KEY_PENDING_URL, downloadUrl)
                        putString(KEY_PENDING_CHANGELOG, release.body)
                    }
                }

                UpdateInfo(
                    latestVersion = latest,
                    downloadUrl = downloadUrl,
                    changelog = release.body,
                    sha256 = sha256
                )
            } else {
                withContext(Dispatchers.IO) {
                    prefs.edit(commit = true) { putString(KEY_LAST_CHECK_DAY, day) }
                }
                null
            }
        } catch (e: Exception) {
            CrashLogManager.logException("UpdateManager", "检查更新失败", e)
            onError?.invoke(e)
            null
        }
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

    private fun normalizeVersion(version: String): String {
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

    /**
     * 下载 APK 并引导安装。
     *
     * 同名安装包已存在时先校验：通过即安装、不重复下载；不通过（含期望哈希缺失）一律删除重下。
     * 进度长时间无推进（15 秒）判为超时失败。
     *
     * @return true 表示已启动安装界面，false 表示下载失败
     */
    suspend fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val fileName = "EchoTideMusic_${updateInfo.latestVersion}.apk"
        val outFile = java.io.File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        // 下载、轮询、文件操作全部在 IO 线程执行，避免 DownloadManager IPC 阻塞主线程
        return withContext(Dispatchers.IO) {
            if (outFile.exists()) {
                if (verifyApkHash(outFile, updateInfo.sha256)) {
                    onProgress(1f)
                    installApk(context, outFile)
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
                        // 完整性校验：与 GitHub 提供的 SHA-256 比对，不符或不可校验一律拒绝安装
                        if (!verifyApkHash(outFile, updateInfo.sha256)) {
                            outFile.delete()
                            onProgress(-1f)
                            return@withContext false
                        }
                        installApk(context, outFile)
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

    // 安装即视为用户已处理该版本，先清掉待更新信息，避免下次冷启动重复提示
    private suspend fun installApk(context: Context, apkFile: java.io.File) {
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
        clearPendingUpdate(context)
    }

    // 校验已下载 APK 的 SHA-256：期望哈希缺失视为不可校验，与不符一律判失败
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

    // 流式计算文件 SHA-256（小写十六进制），避免整包读入内存
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

    // 仅允许 HTTPS 下载地址，防止下载降级到明文传输
    private fun requireHttps(url: String): String {
        if (!url.startsWith("https://")) {
            throw IllegalArgumentException("不安全的下载地址: $url")
        }
        return url
    }

    suspend fun clearPendingUpdate(context: Context) {
        withContext(Dispatchers.IO) {
            prefs(context).edit(commit = true) {
                remove(KEY_PENDING_VERSION)
                remove(KEY_PENDING_URL)
                remove(KEY_PENDING_CHANGELOG)
            }
        }
    }

    suspend fun ignoreVersion(context: Context, version: String) {
        withContext(Dispatchers.IO) {
            prefs(context).edit(commit = true) {
                putString(KEY_IGNORED_VERSION, version)
                remove(KEY_PENDING_VERSION)
                remove(KEY_PENDING_URL)
                remove(KEY_PENDING_CHANGELOG)
            }
        }
    }

    // 逐段按整数比较：字符串比较会把 "1.10" 判为小于 "1.9"
    private fun isNewerVersion(latest: String, current: String): Boolean {
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
