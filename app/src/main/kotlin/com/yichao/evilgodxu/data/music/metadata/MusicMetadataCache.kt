package com.yichao.evilgodxu.data.music.metadata

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.api.parseWordTimedLrcText
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import com.yichao.evilgodxu.data.music.download.sanitizeFileName
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import org.json.JSONArray

// 歌词缓存，封面另存
internal object MusicMetadataCache {
    // 缓存根目录名，Download/YiChao
    internal const val CACHE_DIR_NAME = "YiChao"
    private const val LYRIC_DIR = "Lyrics"

    // 公共下载目录，暂无等价 API
    @Suppress("DEPRECATION")
    internal fun mediaRoot(context: Context): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, CACHE_DIR_NAME) }
        return public ?: context.getExternalFilesDir(null) ?: context.filesDir
    }

    // 歌词目录，与写入端共用路径解析
    internal fun lyricRoot(context: Context): File = File(mediaRoot(context), LYRIC_DIR)

    // 索引名“标题 - 艺术家”；同名曲目共用缓存
    private fun metadataIndexName(title: String, artist: String): String = sanitizeFileName(
        listOf(title, artist)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "unknown" }
    )

    private fun cacheFileName(title: String, artist: String, extension: String): String =
        "${metadataIndexName(title, artist)}.$extension"

    private fun lyricFileName(title: String, artist: String): String = cacheFileName(title, artist, "lrc")

    private fun lyricFile(context: Context, title: String, artist: String): File =
        File(lyricRoot(context), lyricFileName(title, artist))

    // 是否有全部文件访问权限
    private fun hasDirectDownloadAccess(): Boolean = Environment.isExternalStorageManager()

    // 返回绝对路径；直写、MediaStore、私有
    private fun writeCacheFile(context: Context, dirName: String, name: String, bytes: ByteArray): String? {
        if (hasDirectDownloadAccess()) {
            writeToPublicDir(context, dirName, name, bytes)?.let { return it }
        }
        return writeViaMediaStore(context, dirName, name, bytes)
            ?: writeToPrivateDir(context, dirName, name, bytes)
    }

    private fun writeToPublicDir(context: Context, dirName: String, name: String, bytes: ByteArray): String? {
        val dir = File(mediaRoot(context), dirName)
        if (!dir.isDirectory && !runCatching { dir.mkdirs() }.getOrDefault(false)) return null
        val file = File(dir, name)
        return file.absolutePath
            .takeIf { runCatching { file.writeBytes(bytes) }.isSuccess && file.isFile }
    }

    // Downloads 覆盖写；不设 MIME 免改名
    private fun writeViaMediaStore(context: Context, dirName: String, name: String, bytes: ByteArray): String? = try {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = queryDownloadsUri(context, dirName, name)
        val inserted = existing == null
        val uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, downloadsRelativePath(dirName))
                // 标记 pending，避免读到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: return null
        val written = resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
        // 只为新条目发布，改既有会重算落点
        resolver.update(uri, ContentValues().apply {
            if (inserted) put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }, null, null)
        if (!written) return null
        // 同名冲突会改落点，按 DATA 取真实路径
        queryDownloadsPath(context, uri) ?: File(File(mediaRoot(context), dirName), name).absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "MediaStore 写入缓存失败: $dirName/$name", e)
        null
    }

    private fun queryDownloadsPath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    // 按目录+文件名定位 Downloads 条目
    private fun queryDownloadsUri(context: Context, dirName: String, name: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            arrayOf(name, downloadsRelativePath(dirName)),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null }
    }

    // 私有目录兜底，按需创建
    private fun writeToPrivateDir(context: Context, dirName: String, name: String, bytes: ByteArray): String? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, dirName)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, name)
        return file.absolutePath
            .takeIf { runCatching { file.writeBytes(bytes) }.isSuccess && file.isFile }
    }

    // Download/YiChao/<子目录>/
    private fun downloadsRelativePath(dirName: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$CACHE_DIR_NAME/$dirName/"

    fun saveLyrics(context: Context, title: String, artist: String, lines: List<LyricLine>): String? = try {
        val name = lyricFileName(title, artist)
        writeCacheFile(context, LYRIC_DIR, name, encodeLyrics(lines).toByteArray())
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存歌词失败: 标题=${title} 艺术家=${artist}", e)
        null
    }

    // 增强 LRC：行与逐字各带时间戳
    internal fun encodeLyrics(lines: List<LyricLine>): String =
        lines.joinToString("\n") { encodeLyricLine(it) }

    // 单行序列化；不带翻译时避免误改
    internal fun encodeLyricLine(line: LyricLine, includeTranslation: Boolean = true): String {
        val timestamp = lrcTimestamp(line.timeMs)
        val translation = if (includeTranslation) {
            line.translation?.takeIf { it.isNotBlank() }?.let { "[tr]$it[/tr]" }.orEmpty()
        } else {
            ""
        }
        return if (line.words.isEmpty()) {
            "[$timestamp]${line.text}$translation"
        } else {
            "[$timestamp]" + line.words.joinToString("") { "<${lrcTimestamp(it.startMs)}>${it.text}" } + translation
        }
    }

    // 查找歌词缓存路径；不可 stat 但集合存在时同样返回
    fun findLyrics(context: Context, title: String, artist: String): String? {
        val file = lyricFile(context, title, artist)
        if (isValid(context, file.absolutePath)) return file.absolutePath
        return file.absolutePath.takeIf { downloadsUriForFile(context, file) != null }
    }

    // 歌词修改时间毫秒，不可用返回 0；路径不可读时查集合
    fun lyricsModifiedMs(context: Context, location: String): Long {
        if (location.isBlank()) return 0L
        if (isContentLocation(location)) return queryModifiedSeconds(context, location.toUri()) * 1000L
        val file = File(location)
        val modified = runCatching { file.lastModified() }.getOrDefault(0L)
        if (modified > 0L) return modified
        return downloadsUriForFile(context, file)
            ?.let { queryModifiedSeconds(context, it) * 1000L }
            ?: 0L
    }

    fun loadLyrics(context: Context, location: String): List<LyricLine> = try {
        parseLyricsText(readLyricsText(context, location))
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "加载歌词失败: 位置=$location", e)
        emptyList()
    }

    // 兼容逐字增强标签
    fun parseLyricsText(text: String): List<LyricLine> {
        // 空白文本直接返回空列表
        if (text.isBlank()) return emptyList()
        return parseEnhancedLrc(text).ifEmpty { parseJsonLyrics(text) }
    }

    // 幂等；缓存始终存原始时间戳
    fun shiftLyrics(lines: List<LyricLine>, deltaMs: Long): List<LyricLine> =
        lines.map { line ->
            line.copy(
                timeMs = (line.timeMs + deltaMs).coerceAtLeast(0),
                words = line.words.map { word -> word.copy(startMs = (word.startMs + deltaMs).coerceAtLeast(0)) },
            )
        }

    private fun lrcTimestamp(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = ms % 60_000 / 1000
        val millis = ms % 1000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    // 共享增强 LRC 解析，附加旧数据修复
    private fun parseEnhancedLrc(lrc: String): List<LyricLine> =
        repairLegacyWordStarts(parseWordTimedLrcText(lrc))

    // 旧 YRC 重复行首时间，越界时减去
    private fun repairLegacyWordStarts(lines: List<LyricLine>): List<LyricLine> =
        lines.mapIndexed { index, line ->
            val firstWordStart = line.words.firstOrNull()?.startMs
            if (line.timeMs <= 0 || firstWordStart == null) {
                line
            } else {
                val nextLineStart = lines.getOrNull(index + 1)?.timeMs
                // 判据：字起点早于下一行；无下一行时早于行首两倍
                val inconsistent = nextLineStart
                    ?.let { it > line.timeMs && firstWordStart >= it }
                    ?: (firstWordStart >= line.timeMs * 2)
                if (inconsistent) {
                    line.copy(words = line.words.map { it.copy(startMs = (it.startMs - line.timeMs).coerceAtLeast(0)) })
                } else {
                    line
                }
            }
        }

    // 旧版 JSON 歌词回退解析
    private fun parseJsonLyrics(text: String): List<LyricLine> {
        // 含 LRC 特征时不按 JSON 解析
        if (LRC_TIMESTAMP_PATTERN.containsMatchIn(text) || text.contains(LRC_WORD_ARROW)) return emptyList()
        return runCatching { parseJsonArray(text) }.getOrDefault(emptyList())
    }

    // LRC 时间戳；[ 后须紧跟数字，避开 JSON 歌词
    private val LRC_TIMESTAMP_PATTERN = Regex("""\[\d+:\d+(?::\d+)?(?:[.:]\d+)?]""")
    // 逐字增强 LRC 的区间箭头标记
    private const val LRC_WORD_ARROW = "→"

    private fun parseJsonArray(text: String): List<LyricLine> {
        val array = JSONArray(text)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val words = item.optJSONArray("words") ?: JSONArray()
            LyricLine(
                timeMs = item.getLong("timeMs"),
                text = item.getString("text"),
                words = List(words.length()) { wordIndex ->
                    val word = words.getJSONObject(wordIndex)
                    LyricWord(word.getLong("startMs"), word.getLong("durationMs"), word.getString("text"))
                }
            )
        }
    }

    // 新写绝对路径，旧 URI 兼容读
    private fun isContentLocation(location: String): Boolean =
        location.startsWith(ContentResolver.SCHEME_CONTENT)

    // 仅探测路径是否存在；content URI 视为可用
    fun isValid(context: Context, location: String): Boolean = when {
        location.isBlank() -> false
        isContentLocation(location) -> true
        else -> File(location).let { it.isFile && it.length() > 0 }
    }

    // 读缓存文本：路径直读，失败按目录查
    internal fun readLyricsText(context: Context, location: String): String {
        if (isContentLocation(location)) return readContent(context, location.toUri()).orEmpty()
        val file = File(location)
        runCatching { file.readText() }.getOrNull()?.let { return it }
        return readContent(context, downloadsUriForFile(context, file) ?: return "").orEmpty()
    }

    private fun readContent(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // 由文件路径查 Downloads 条目
    private fun downloadsUriForFile(context: Context, file: File): Uri? {
        val dirName = file.parentFile?.name ?: return null
        return queryDownloadsUri(context, dirName, file.name)
    }

    // 只删本应用两处落点内的歌词，避免误删用户文件
    fun deleteLyrics(context: Context, location: String) {
        if (location.isBlank()) return
        if (isContentLocation(location)) {
            deleteEntry(context, location.toUri())
            return
        }
        val file = File(location)
        if (file.parentFile?.absolutePath !in lyricRoots(context)) return
        val uri = downloadsUriForFile(context, file)
        if (uri != null && deleteEntry(context, uri)) return
        runCatching { file.delete() }
    }

    // 歌词两处落点：公共目录与私有目录
    private fun lyricRoots(context: Context): Set<String> = buildSet {
        add(lyricRoot(context).absolutePath)
        context.getExternalFilesDir(null)?.let { add(File(it, LYRIC_DIR).absolutePath) }
    }

    private fun deleteEntry(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    private fun queryModifiedSeconds(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)
}
