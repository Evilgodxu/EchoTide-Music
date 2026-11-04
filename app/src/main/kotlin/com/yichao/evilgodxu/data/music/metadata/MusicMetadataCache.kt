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

// 歌词缓存读写工具：全部为无状态函数，按需传入 Context。
// 封面不在此列：封面由显示端按需取系统略缩图或音频内嵌封面，仅当前曲目的一张缩略图另行落盘（见 CurrentCoverCache）
internal object MusicMetadataCache {
    // 公共下载目录下的应用缓存根目录名，与在线音频缓存 Download/YiChao/Audio 保持同级。
    // 下载器写入在线歌曲条目时按此拼装相对路径，目录名只在此处定义一次
    internal const val CACHE_DIR_NAME = "YiChao"
    private const val LYRIC_DIR = "Lyrics"

    // 缓存根目录：系统公共下载目录 Download/YiChao。
    // 具备全部文件访问权限时直写文件系统；权限缺失时经 MediaStore Downloads 集合写入自身条目
    // （Android 11+ 对自身写入的文件保留路径读取能力），上层调用方统一按返回的绝对路径使用，
    // 不受分区存储影响。
    // getExternalStoragePublicDirectory 为获取公共下载目录路径的唯一接口，无新版等价实现
    // 缓存台账统计同目录下的同级缓存（如在线音频）时也按此定位，避免路径出现第二份事实
    @Suppress("DEPRECATION")
    internal fun mediaRoot(context: Context): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, CACHE_DIR_NAME) }
        return public ?: context.getExternalFilesDir(null) ?: context.filesDir
    }

    // 供缓存台账统计歌词占用：与写入端共用同一路径解析，不另立一份
    internal fun lyricRoot(context: Context): File = File(mediaRoot(context), LYRIC_DIR)

    // 缓存索引名：按“标题 - 艺术家”命名，空字段自动忽略，两者皆空回退 unknown。
    // 封面与歌词共用同一索引，于是文件名可由曲目元数据直接推出——不依赖曲目里记录的引用即可查回，
    // 代价是标题与艺术家都相同的曲目（含超过 80 字符被截断后同名前缀的曲目）共享同一份缓存文件
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

    // 是否具备直接读写公共下载目录的特殊权限（全部文件访问）
    private fun hasDirectDownloadAccess(): Boolean = Environment.isExternalStorageManager()

    // 写入缓存文件，落点一律返回绝对路径：位置由 目录+文件名 唯一确定，不把 MediaStore 条目 ID
    // 写进曲目——条目可能被重建或清理，ID 会失效，路径不会。
    // 有全部文件访问时直写文件系统：可复用/覆盖目录中已有的同名文件，不会因同名冲突产生副本。
    // 没有该权限时经 Downloads 集合写入：向公共下载目录贡献自身条目不需要任何存储权限（Android 10+），
    // 与在线音频缓存的写入方式一致。两条路都失败才回退应用专属目录。
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

    // MediaStore Downloads 写入：按 目录+文件名 复用既有条目覆盖写，避免同名重复文件。
    // 不显式设置 MIME_TYPE：集合会按文件名推断类型；显式给 text/plain 时其默认扩展名是 .txt，
    // 与 .lrc 不匹配，集合会把文件改名为 "<原名> (1).txt"
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
                // 写入期间标记 pending，避免媒体扫描读到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: return null
        val written = resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
        // 只对新建条目发布；既有条目不碰 IS_PENDING——它属于布局列，改它会触发集合按
        // DISPLAY_NAME/MIME 重新推算落点，旧名称与 MIME 不匹配时会再改名甚至撞唯一约束
        resolver.update(uri, ContentValues().apply {
            if (inserted) put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }, null, null)
        if (!written) return null
        // 条目实际落点可能因同名冲突带序号，按 DATA 取回真实路径，避免把曲目指向不存在的文件
        queryDownloadsPath(context, uri) ?: File(File(mediaRoot(context), dirName), name).absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "MediaStore 写入缓存失败: $dirName/$name", e)
        null
    }

    private fun queryDownloadsPath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    // 按 目录+文件名 定位 Downloads 集合中的既有条目
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

    // 应用专属目录兜底：MediaStore 写入失败时保证缓存仍可落盘。
    // 目录只在真正走到这一步时才创建，命中 MediaStore 时不会留下任何空目录
    private fun writeToPrivateDir(context: Context, dirName: String, name: String, bytes: ByteArray): String? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, dirName)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, name)
        return file.absolutePath
            .takeIf { runCatching { file.writeBytes(bytes) }.isSuccess && file.isFile }
    }

    // Downloads 集合的目录相对路径：Download/YiChao/<子目录>/
    private fun downloadsRelativePath(dirName: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$CACHE_DIR_NAME/$dirName/"

    fun saveLyrics(context: Context, title: String, artist: String, lines: List<LyricLine>): String? = try {
        val name = lyricFileName(title, artist)
        writeCacheFile(context, LYRIC_DIR, name, encodeLyrics(lines).toByteArray())
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存歌词失败: 标题=${title} 艺术家=${artist}", e)
        null
    }

    // 歌词按增强 LRC 文本序列化：标准 [mm:ss.xxx] 行 + 行内 <mm:ss.xxx> 逐字时间戳，翻译以 [tr][/tr] 追加
    internal fun encodeLyrics(lines: List<LyricLine>): String =
        lines.joinToString("\n") { encodeLyricLine(it) }

    // 单行序列化。includeTranslation=false 供「原文与翻译分属不同编辑入口」的场景使用：
    // 只暴露时间戳/逐字/文本，翻译由独立入口维护，避免编辑原文时误改翻译
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

    // 按“标题 - 艺术家”查找已存在的歌词缓存文件：在线播放/手动刷新保存的 .lrc 可直接复用。
    // 返回绝对路径（目录+文件名唯一确定）；路径不可 stat 但集合中确认存在时同样返回该路径，
    // 读取入口会改走集合（见 readLyricsText）
    fun findLyrics(context: Context, title: String, artist: String): String? {
        val file = lyricFile(context, title, artist)
        if (isValid(context, file.absolutePath)) return file.absolutePath
        return file.absolutePath.takeIf { downloadsUriForFile(context, file) != null }
    }

    // 歌词缓存文件的最后修改时间（毫秒）：与音频内嵌歌词择新时用作比较基准，不可用时返回 0。
    // 路径不可 stat 时同读取入口一样回到 Downloads 集合取条目修改时间
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

    // 兼容逐字增强标签；调用方为 LRC 导入与内嵌歌词读取
    fun parseLyricsText(text: String): List<LyricLine> {
        // 空/空白文本直接返回空列表，避免进入 JSON 兜底解析抛空值异常
        if (text.isBlank()) return emptyList()
        return parseEnhancedLrc(text).ifEmpty { parseJsonLyrics(text) }
    }

    // 应用幂等；缓存文件保存的始终是原始时间戳
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

    // 增强 LRC 解析交由共享实现，本地与在线歌词走同一份解析逻辑；此处只叠加旧版数据的修复
    private fun parseEnhancedLrc(lrc: String): List<LyricLine> =
        repairLegacyWordStarts(parseWordTimedLrcText(lrc))

    // 兼容修复：早期 YRC 解析把字标签的绝对时间又叠加了一次行首时间，使字的起点落在本行区间之外
    // （甚至晚于下一行起点）——这类值不可能成立，回退减去行首时间还原为绝对时间。
    // 数据正常（字起点落在本行区间内）时不改动任何内容。
    private fun repairLegacyWordStarts(lines: List<LyricLine>): List<LyricLine> =
        lines.mapIndexed { index, line ->
            val firstWordStart = line.words.firstOrNull()?.startMs
            if (line.timeMs <= 0 || firstWordStart == null) {
                line
            } else {
                val nextLineStart = lines.getOrNull(index + 1)?.timeMs
                // 判据：字起点不得晚于下一行起点；无下一行（或下一行时间相同）时不得晚于行首的两倍
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

    // 旧版 JSON 缓存回退解析：命中 LRC 特征或强转失败时均返回空，不再抛异常
    private fun parseJsonLyrics(text: String): List<LyricLine> {
        // 命中 LRC 特征（[mm:ss] 时间戳行或 → 逐字标记）时不视为 JSON，避免强转 JSONArray 抛异常
        if (LRC_TIMESTAMP_PATTERN.containsMatchIn(text) || text.contains(LRC_WORD_ARROW)) return emptyList()
        return runCatching { parseJsonArray(text) }.getOrDefault(emptyList())
    }

    // 标准 LRC 时间戳 [mm:ss] / [mm:ss.xxx]（含小时制 [hh:mm:ss.xxx]）；要求 [ 后紧跟数字，避免误判旧版 JSON 歌词（以 [{ 开头）
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

    // 缓存位置：新写入的一律是绝对路径；旧版遗留的 content URI 仍按原样读取，供升级过渡
    private fun isContentLocation(location: String): Boolean =
        location.startsWith(ContentResolver.SCHEME_CONTENT)

    // 缓存是否可用。仅在路径形态下做存在性探测（stat 开销远低于逐首 query）；
    // content URI 不做探测：条目即使已被外部删除，读取入口也只是按空文本回退重建，
    // 逐首 query 反而会把冷启动拖成数百次 IPC
    fun isValid(context: Context, location: String): Boolean = when {
        location.isBlank() -> false
        isContentLocation(location) -> true
        else -> File(location).let { it.isFile && it.length() > 0 }
    }

    // 读取缓存文本：路径可读时直读；无「全部文件访问」时公共下载目录下的自身文件不一定能按路径读，
    // 按 目录+文件名 回到 Downloads 集合读同一份内容。位置已不存在（用户清理了下载目录等）
    // 按空文本处理，交由调用方回退重建，不当作加载失败
    internal fun readLyricsText(context: Context, location: String): String {
        if (isContentLocation(location)) return readContent(context, location.toUri()).orEmpty()
        val file = File(location)
        runCatching { file.readText() }.getOrNull()?.let { return it }
        return readContent(context, downloadsUriForFile(context, file) ?: return "").orEmpty()
    }

    private fun readContent(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // 由缓存文件路径回到 Downloads 集合中的条目：自身条目经集合始终可访问
    private fun downloadsUriForFile(context: Context, file: File): Uri? {
        val dirName = file.parentFile?.name ?: return null
        return queryDownloadsUri(context, dirName, file.name)
    }

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
