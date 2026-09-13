package com.yichao.evilgodxu.data.music.metadata

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import com.yichao.evilgodxu.data.music.download.sanitizeFileName
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

// 封面/歌词缓存读写工具：全部为无状态静态函数，按需传入 Context，以 object 单例形态提供
internal object MusicMetadataCache {
    // 封面保存上限与显示端对齐（2K）：覆盖折叠屏/平板横屏等最大显示场景，超过部分永不显示；
    // 位图内存峰值约 2048²×4 ≈ 16MB，解码后即压缩保存并回收，不常驻
    private const val COVER_MAX_EDGE = 2048

    // 公共下载目录下的应用缓存根目录名，与在线音频缓存 Download/YiChao/Audio 保持同级。
    // 下载器写入在线歌曲条目时按此拼装相对路径，目录名只在此处定义一次
    internal const val CACHE_DIR_NAME = "YiChao"
    private const val COVER_DIR = "Cover"
    private const val LYRIC_DIR = "Lyrics"

    // 封面缓存允许的扩展名（WEBP 为主、PNG 兜底两路写入），清理时防误删目录内其他用途文件
    private val COVER_CACHE_EXTENSIONS = setOf("webp", "png")
    // 歌词缓存允许的扩展名
    private val LYRIC_CACHE_EXTENSIONS = setOf("lrc")
    // 孤儿回收的作用域上限：只认这两个子目录，新增缓存类型须独立建目录 + 独立扩展名白名单
    private val CACHE_DIR_NAMES = listOf(COVER_DIR, LYRIC_DIR)
    // 立即回收的写入竞态宽限期：外部写封面先落盘后写曲目引用，晚于该窗口的缓存可能尚未被引用，跳过避免误删
    private const val ORPHAN_GRACE_MS = 10_000L
    // 自动回收窗口：缓存文件须在每次可信扫描中都无引用、持续该时长才回收。
    // 单次判定不足以采信 —— 引用集取自本次扫描结果，存储未挂载 / 媒体条目暂缺会让它系统性偏小
    private const val ORPHAN_RECLAIM_WINDOW_DAYS = 3
    private const val ORPHAN_RECLAIM_WINDOW_MS = ORPHAN_RECLAIM_WINDOW_DAYS * 24 * 60 * 60 * 1000L
    // 无引用首见时刻的记录文件（应用私有目录，与缓存目录隔离）：丢失即时钟重置，只推迟回收
    private const val RECLAIM_RECORD_FILE = "orphan_reclaim.json"
    private const val RECLAIM_RECORD_KEY = "firstSeenUnreferencedAt"
    private const val TAG = "MusicMetadataCache"

    // 缓存根目录：系统公共下载目录 Download/YiChao。
    // 具备全部文件访问权限时直写文件系统并附带 .nomedia 防止封面混入相册；
    // 权限缺失时经 MediaStore Downloads 集合写入自身条目（Android 11+ 对自身写入的
    // 文件保留路径读取能力），上层调用方统一按返回的绝对路径使用，不受分区存储影响。
    // getExternalStoragePublicDirectory 为获取公共下载目录路径的唯一接口，无新版等价实现
    // 缓存台账统计同目录下的同级缓存（如在线音频）时也按此定位，避免路径出现第二份事实
    @Suppress("DEPRECATION")
    internal fun mediaRoot(context: Context): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, CACHE_DIR_NAME) }
        return public ?: context.getExternalFilesDir(null) ?: context.filesDir
    }

    internal fun coverRoot(context: Context): File = File(mediaRoot(context), COVER_DIR)
    // 供缓存台账统计歌词占用：与写入端共用同一路径解析，不另立一份
    internal fun lyricRoot(context: Context): File = File(mediaRoot(context), LYRIC_DIR)

    // 歌词文件按“标题 - 艺术家”命名，空字段自动忽略，保证可读且避免同名覆盖
    private fun lyricFileName(title: String, artist: String): String {
        val name = listOf(title, artist)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "unknown" }
        return "${sanitizeFileName(name)}.lrc"
    }

    private fun lyricFile(context: Context, title: String, artist: String): File =
        File(lyricRoot(context), lyricFileName(title, artist))

    // 标记目录不被系统媒体扫描器收录，避免封面图出现在相册
    private fun ensureNoMedia(dir: File) = runCatching {
        File(dir, ".nomedia").apply { if (!exists()) createNewFile() }
    }

    // 是否具备直接读写公共下载目录的特殊权限（全部文件访问）
    private fun hasDirectDownloadAccess(): Boolean = Environment.isExternalStorageManager()

    // 写入缓存文件：有全部文件权限时直写文件系统；否则经 MediaStore Downloads 集合写入
    // 自身条目，mediaRoot 两路返回同一绝对路径。直写或 MediaStore 均失败时回退应用专属目录
    private fun writeCacheFile(context: Context, dirName: String, name: String, bytes: ByteArray): File? {
        if (!hasDirectDownloadAccess()) {
            return writeViaMediaStore(context, dirName, name, bytes)
                ?: writeToPrivateDir(context, dirName, name, bytes)
        }
        val dir = File(mediaRoot(context), dirName)
        if (!dir.isDirectory && !runCatching { dir.mkdirs() }.getOrDefault(false)) return null
        // 仅封面目录需 .nomedia 防止混入相册，歌词文本不受媒体扫描影响
        if (dirName == COVER_DIR) ensureNoMedia(dir)
        val file = File(dir, name)
        return if (runCatching { file.writeBytes(bytes) }.isSuccess && file.isFile) file else null
    }

    // MediaStore Downloads 兜底写入：按 目录+文件名 复用既有条目覆盖写，避免同名重复文件
    private fun writeViaMediaStore(context: Context, dirName: String, name: String, bytes: ByteArray): File? = try {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = queryDownloadsUri(context, dirName, name) ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, cacheMimeType(name))
                put(MediaStore.Downloads.RELATIVE_PATH, downloadsRelativePath(dirName))
            },
        ) ?: return null
        // 写入期间标记 pending，避免媒体扫描读到半截文件
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }, null, null)
        val written = resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
        // 写入完成并刷新条目修改时间：清理机制以 DATE_MODIFIED 判定最近写入宽限期，
        // 复用既有条目时该字段为上次写入时间，不刷新会令宽限期失效，误删刚落盘的缓存
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }, null, null)
        if (!written) return null
        queryDataPath(context, dirName, name)?.let(::File)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "MediaStore 写入缓存失败: $dirName/$name", e)
        null
    }

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

    // 查询自身条目的真实磁盘路径：自身写入的条目，其路径可直接由本应用回读
    private fun queryDataPath(context: Context, dirName: String, name: String): String? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        return context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads.DATA),
            selection,
            arrayOf(name, downloadsRelativePath(dirName)),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    // Downloads 集合的目录相对路径：Download/YiChao/<子目录>/
    private fun downloadsRelativePath(dirName: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$CACHE_DIR_NAME/$dirName/"

    // 按文件名推断缓存条目的 MIME 类型
    private fun cacheMimeType(name: String): String = when (name.substringAfterLast('.', "")) {
        "webp" -> "image/webp"
        "png" -> "image/png"
        "lrc" -> "text/plain"
        else -> "application/octet-stream"
    }

    // 应用专属目录兜底：MediaStore 与公共目录直写均不可用时保证缓存仍可落盘
    private fun writeToPrivateDir(context: Context, dirName: String, name: String, bytes: ByteArray): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val dir = File(root, dirName)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // 仅封面目录需 .nomedia 防止混入相册，歌词文本不受媒体扫描影响
        if (dirName == COVER_DIR) ensureNoMedia(dir)
        val file = File(dir, name)
        return if (runCatching { file.writeBytes(bytes) }.isSuccess && file.isFile) file else null
    }

    /** 按最长边等比高质量解码；maxEdge 可调以适配显示场景（显示端无需保存上限全尺寸位图） */
    fun decodeSampledBitmap(bytes: ByteArray, maxEdge: Int = COVER_MAX_EDGE): Bitmap? {
        // 用 ImageDecoder 替代 BitmapFactory.inSampleSize：
        // inSampleSize 为最近邻点采样，4K 等高分辨率封面降采样会产生混叠锯齿（解码时即固化）；
        // ImageDecoder 按目标尺寸高质量滤波缩放，直接解码到目标分辨率，无锯齿且内存可控
        return runCatching {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 软件位图：保证后续 compress(WEBP/PNG) 与 recycle() 可用
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = maxOf(info.size.width, info.size.height)
                if (longEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longEdge
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                // 统一 sRGB 输出，避免广色域封面在不同设备上渲染偏差
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        }.getOrNull()
    }

    fun saveCover(context: Context, id: Long, originalBytes: ByteArray): String? = try {
        val bitmap = decodeSampledBitmap(originalBytes) ?: return null
        try {
            saveCover(context, id, bitmap)
        } finally {
            bitmap.recycle()
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败: 来源=${originalBytes.size}B", e)
        null
    }

    fun saveCover(context: Context, id: Long, bitmap: Bitmap): String? = try {
        // 先编码到内存并取内容哈希作为文件名：同图共享同一文件，天然去重
        val webpBytes = ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, out)) out.toByteArray() else null
        }
        if (webpBytes != null) {
            val key = contentKey(webpBytes)
            val cached = writeCacheFile(context, COVER_DIR, "$key.webp", webpBytes)
            if (cached != null) return cached.absolutePath
        }
        // WEBP 编码/写入失败，回退为 PNG 原样保存
        val pngBytes = ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else return null
        }
        writeCacheFile(context, COVER_DIR, "${contentKey(pngBytes)}.png", pngBytes)?.absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存封面失败: 封面尺寸=${bitmap.width}x${bitmap.height}", e)
        null
    }

    // 封面内容 SHA-256 前 8 字节的十六进制作为缓存文件名，实现同图去重
    private fun contentKey(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun saveLyrics(context: Context, title: String, artist: String, lines: List<LyricLine>): String? = try {
        val name = lyricFileName(title, artist)
        val file = writeCacheFile(context, LYRIC_DIR, name, encodeLyrics(lines).toByteArray()) ?: return null
        file.absolutePath
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "保存歌词失败: 标题=${title} 艺术家=${artist}", e)
        null
    }

    // 歌词按增强 LRC 文本序列化：标准 [mm:ss.xxx] 行 + 行内 <mm:ss.xxx> 逐字时间戳，翻译以 [tr][/tr] 追加
    internal fun encodeLyrics(lines: List<LyricLine>): String =
        lines.joinToString("\n") { line ->
            val timestamp = lrcTimestamp(line.timeMs)
            val translation = line.translation?.takeIf { it.isNotBlank() }?.let { "[tr]$it[/tr]" }.orEmpty()
            if (line.words.isEmpty()) "[$timestamp]${line.text}$translation"
            else "[$timestamp]" + line.words.joinToString("") { "<${lrcTimestamp(it.startMs)}>${it.text}" } + translation
        }

    // 按“标题 - 艺术家”查找已存在的歌词缓存文件：在线播放/手动刷新保存的 .lrc 可直接复用
    fun findLyrics(context: Context, title: String, artist: String): String? {
        val file = lyricFile(context, title, artist)
        return file.absolutePath.takeIf { isValid(it) }
    }

    fun loadLyrics(path: String): List<LyricLine> = try {
        parseLyricsText(File(path).readText())
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "加载歌词失败: 路径=$path", e)
        emptyList()
    }

    // 解析本地导入的 LRC 文本（兼容逐字增强标签），供外部导入歌词时复用
    fun parseLyricsText(text: String): List<LyricLine> {
        // 空/空白文本直接返回空列表，避免进入 JSON 兜底解析抛空值异常
        if (text.isBlank()) return emptyList()
        return parseEnhancedLrc(text).ifEmpty { parseJsonLyrics(text) }
    }

    // 整体平移歌词时间轴（手动微调用）：应用幂等，缓存文件保存的始终是原始时间戳
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

    // 增强 LRC 解析：兼容纯文本行、行内 <mm:ss.xxx> 逐字标签与 [tr][/tr] 翻译块；
    // 时间戳支持 [mm:ss(.xxx)] 与长音频常用的小时制 [hh:mm:ss(.xxx)]，小数位 1~3 位均可，前导零可忽略
    private fun parseEnhancedLrc(lrc: String): List<LyricLine> {
        val linePattern = Regex("""\[(?:(\d+):)?(\d+):(\d+)(?:\.(\d+))?](.*)""")
        val wordPattern = Regex("""<(?:(\d+):)?(\d+):(\d+)(?:\.(\d+))?>([^<]*)""")
        val transPattern = Regex("""\[tr](.*?)\[/tr]""")
        val lines = lrc.lineSequence().mapNotNull { rawLine ->
            val match = linePattern.find(rawLine) ?: return@mapNotNull null
            val timeMs = (match.groupValues[1].toLongOrNull() ?: 0L) * 3_600_000 +
                match.groupValues[2].toLong() * 60_000 +
                match.groupValues[3].toLong() * 1000 +
                match.groupValues[4].padEnd(3, '0').take(3).toLong()
            val content = match.groupValues[5]
            val translation = transPattern.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            val cleanContent = transPattern.replace(content, "").trim()
            val words = wordPattern.findAll(cleanContent).map { word ->
                LyricWord(
                    startMs = (word.groupValues[1].toLongOrNull() ?: 0L) * 3_600_000 +
                        word.groupValues[2].toLong() * 60_000 +
                        word.groupValues[3].toLong() * 1000 +
                        word.groupValues[4].padEnd(3, '0').take(3).toLong(),
                    durationMs = 0L,
                    text = word.groupValues[5]
                )
            }.filter { it.text.isNotEmpty() }.toList()
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else cleanContent.trim()
            LyricLine(timeMs, text, words, translation).takeIf { it.text.isNotBlank() }
        }.sortedBy { it.timeMs }.toList()
        return repairLegacyWordStarts(lines)
    }

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

    fun isValid(path: String): Boolean = path.isNotBlank() && File(path).let { it.isFile && it.length() > 0 }

    // 是否为新版内容哈希命名的封面缓存（旧版按歌曲 id 命名，用于一次性迁移为哈希命名）
    fun isHashKeyFileName(path: String): Boolean = runCatching {
        File(path).nameWithoutExtension.matches(HASH_KEY_REGEX)
    }.getOrDefault(false)

    private val HASH_KEY_REGEX = Regex("[0-9a-f]{16}")

    fun loadCoverBytes(path: String): ByteArray? = try {
        if (!isValid(path)) null else File(path).readBytes()
    } catch (e: Exception) {
        CrashLogManager.logException("MusicMetadataCache", "读取封面文件失败: $path", e)
        null
    }

    // ===== 孤儿缓存回收 =====
    //
    // 作用域不变量（只可收窄，不可放宽）：本机制只遍历封面与歌词两个缓存子目录（CACHE_DIR_NAMES），
    // 且只删扩展名命中白名单的文件。在线音频缓存 Download/YiChao/Audio、用户媒体目录与任何音频文件
    // 都不在作用域内 —— 音频只由用户显式删除曲目（deleteSongPermanently）移除。
    //
    // 引用集由调用方按「全量库备份 ∪ 当前歌单」构建，跨歌单共享的文件因存在引用而不会被误删；
    // 引用集为空（如歌曲库尚未加载）直接整体跳过，避免误删全部缓存。
    // 两条处置策略：立即回收（引用集已确认，用于用户显式删除曲目）与窗口回收（自动路径专用）。

    // 未被引用的缓存候选：直扫文件系统与 MediaStore 枚举两路合并后按绝对路径去重
    private data class OrphanCandidate(
        val path: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long,
        // MediaStore 条目 ID：直删文件失败时经条目删除兜底
        val mediaStoreId: Long?,
    )

    /** 立即回收：引用集由调用方确认（用户显式删除曲目），仅保留写入竞态宽限期 */
    fun cleanupOrphanedMetadata(context: Context, referencedPaths: Set<String>) {
        val referenced = normalizedReferences(referencedPaths) ?: return
        val now = System.currentTimeMillis()
        val reclaimable = collectOrphans(context, referenced)
            .filter { now - it.lastModifiedMs > ORPHAN_GRACE_MS }
        deleteOrphans(context, reclaimable, "立即回收")
    }

    /**
     * 窗口回收（自动路径专用）：仅回收「每次可信扫描都无引用、且已持续 [ORPHAN_RECLAIM_WINDOW_DAYS] 天」的文件。
     *
     * 首次观察到的无引用文件只登记首见时刻、不删除：引用集取自本次扫描结果，存储未挂载或媒体条目暂缺
     * 会让它系统性偏小，单次判定不足以采信。期间一旦被重新引用或文件消失，记录即清除、时钟不推进。
     * 记录存于应用私有目录；损坏或丢失时按「无记录」处理，只推迟回收，不会误删。
     */
    fun reclaimStaleOrphans(context: Context, referencedPaths: Set<String>) {
        val referenced = normalizedReferences(referencedPaths) ?: return
        val now = System.currentTimeMillis()
        val orphans = collectOrphans(context, referenced)
        val orphanPaths = orphans.mapTo(mutableSetOf()) { it.path }
        val firstSeenAt = loadReclaimRecords(context)
        // 已被重新引用、或已不在盘上的记录一律清除
        firstSeenAt.keys.retainAll(orphanPaths)
        val reclaimable = mutableListOf<OrphanCandidate>()
        orphans.forEach { orphan ->
            val seenAt = firstSeenAt[orphan.path]
            when {
                // 首次观察到无引用：只登记首见时刻，本次不回收
                seenAt == null -> firstSeenAt[orphan.path] = now
                now - seenAt >= ORPHAN_RECLAIM_WINDOW_MS -> {
                    firstSeenAt.remove(orphan.path)
                    reclaimable += orphan
                }
            }
        }
        saveReclaimRecords(context, firstSeenAt)
        deleteOrphans(context, reclaimable, "连续${ORPHAN_RECLAIM_WINDOW_DAYS}天无引用")
    }

    // 引用集归一：空引用集说明歌曲库尚未加载，此时任何缓存都"看似无引用"，必须整体跳过
    private fun normalizedReferences(referencedPaths: Set<String>): Set<String>? =
        referencedPaths.filter(String::isNotBlank).toSet().takeIf { it.isNotEmpty() }

    // 枚举所有根目录下未被引用的缓存文件。双路互为补充：公共目录无全部文件权限时不可直扫（返回 null
    // 或抛安全异常），由 MediaStore 枚举自身条目兜底；任一路命中的候选都会被回收
    private fun collectOrphans(context: Context, referenced: Set<String>): List<OrphanCandidate> {
        val byPath = LinkedHashMap<String, OrphanCandidate>()
        val mediaRoot = mediaRoot(context)
        val roots = buildList {
            add(mediaRoot)
            context.getExternalFilesDir(null)
                ?.takeIf { it.path != mediaRoot.path }
                ?.let { add(it) }
        }
        CACHE_DIR_NAMES.forEach { dirName ->
            roots.forEach { root -> collectOrphanFiles(File(root, dirName), referenced, byPath) }
            collectOrphanMediaStore(context, dirName, referenced, byPath)
        }
        return byPath.values.toList()
    }

    private fun collectOrphanFiles(
        dir: File,
        referenced: Set<String>,
        into: MutableMap<String, OrphanCandidate>,
    ) {
        // 仅封面目录保留 .nomedia，歌词目录残留的旧 .nomedia 一并清理
        val isCoverDir = dir.name == COVER_DIR
        val allowed = if (isCoverDir) COVER_CACHE_EXTENSIONS else LYRIC_CACHE_EXTENSIONS
        runCatching { dir.listFiles() }.getOrNull().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name == ".nomedia") {
                if (!isCoverDir) runCatching { file.delete() }
                return@forEach
            }
            // 仅清理明确属于缓存的扩展名，防止误删目录内其他用途文件
            if (file.extension !in allowed) return@forEach
            if (file.absolutePath in referenced) return@forEach
            into[file.absolutePath] = OrphanCandidate(
                path = file.absolutePath,
                lastModifiedMs = file.lastModified(),
                sizeBytes = file.length(),
                mediaStoreId = null,
            )
        }
    }

    private fun collectOrphanMediaStore(
        context: Context,
        dirName: String,
        referenced: Set<String>,
        into: MutableMap<String, OrphanCandidate>,
    ) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=?"
        try {
            context.contentResolver.query(collection, projection, selection, arrayOf(downloadsRelativePath(dirName)), null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val path = cursor.getString(1) ?: continue
                        if (path in referenced) continue
                        val existing = into[path]
                        // 直扫已列出同一文件时只补上条目 ID，供直删失败时经 MediaStore 兜底
                        if (existing != null) {
                            into[path] = existing.copy(mediaStoreId = id)
                            continue
                        }
                        into[path] = OrphanCandidate(
                            path = path,
                            // MediaStore 的修改时间为秒精度
                            lastModifiedMs = cursor.getLong(3) * 1000L,
                            sizeBytes = cursor.getLong(2),
                            mediaStoreId = id,
                        )
                    }
                }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicMetadataCache", "MediaStore 枚举孤儿缓存失败: $dirName", e)
        }
    }

    // 删除候选并落日志（目录、文件数、释放字节）：误删排查依赖此记录，不要静默删除
    private fun deleteOrphans(context: Context, orphans: List<OrphanCandidate>, reason: String) {
        if (orphans.isEmpty()) return
        var deleted = 0
        var freedBytes = 0L
        orphans.forEach { orphan ->
            val byFile = runCatching { File(orphan.path).delete() }.getOrDefault(false)
            val removed = byFile || (orphan.mediaStoreId?.let { deleteMediaStoreEntry(context, it) } == true)
            if (removed) {
                deleted++
                freedBytes += orphan.sizeBytes
            }
        }
        if (deleted > 0) Log.i(TAG, "回收孤儿缓存[$reason] 文件数=$deleted 释放=$freedBytes B")
    }

    private fun deleteMediaStoreEntry(context: Context, id: Long): Boolean = runCatching {
        context.contentResolver.delete(
            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null
        ) > 0
    }.getOrDefault(false)

    private fun reclaimRecordFile(context: Context): File = File(context.filesDir, RECLAIM_RECORD_FILE)

    // 读取「无引用首见时刻」记录；解析失败按无记录处理，只推迟回收
    private fun loadReclaimRecords(context: Context): MutableMap<String, Long> = runCatching {
        val file = reclaimRecordFile(context)
        if (!file.isFile) return@runCatching mutableMapOf()
        val records = JSONObject(file.readText()).optJSONObject(RECLAIM_RECORD_KEY)
            ?: return@runCatching mutableMapOf()
        val result = mutableMapOf<String, Long>()
        records.keys().forEach { path -> result[path] = records.optLong(path) }
        result
    }.getOrElse { mutableMapOf() }

    private fun saveReclaimRecords(context: Context, records: Map<String, Long>) = runCatching {
        val encoded = JSONObject()
        records.forEach { (path, seenAt) -> encoded.put(path, seenAt) }
        reclaimRecordFile(context).writeText(JSONObject().put(RECLAIM_RECORD_KEY, encoded).toString())
    }.onFailure {
        CrashLogManager.logException("MusicMetadataCache", "写入孤儿回收记录失败", it)
    }
}
