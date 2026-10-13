package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.provider.MediaStore
import com.yichao.evilgodxu.data.music.model.MusicTrack

/**
 * 内嵌标签补全：用音频文件自身的文本标签补齐平台元数据提取器读不到的标题 / 艺术家 / 专辑。
 *
 * 只服务平台读不出标签的容器（WAV，见 [LosslessContainerTags]）：
 * 这些容器的标签不在 MediaStore 的支持范围内，MediaProvider 只能给出文件名派生的标题与
 * <unknown> 占位艺术家。MP3 / FLAC / M4A / Ogg 的标签平台读得到，不在本对象范围内，
 * 一律不为它们打开文件 —— 打开文件是本流程唯一的开销，判定必须收在容器维度上而非标签维度上。
 *
 * 两段式分工：刷新主路径只做 [carryOver]（纯字段比较，不读文件），扫描结果立即可用；
 * 仍待补全的部分由补全通道在后台按 [isPending] 筛选、逐首 [resolve] 回写，
 * 于是同一文件只在首次遇到时读取一次，且读取不占用刷新返回的时间。
 */
internal object EmbeddedTagBackfill {

    /** 平台读不出标签的容器扩展名，用于在打开文件之前排除绝大多数曲目 */
    private val CONTAINER_EXTENSIONS = setOf("wav")

    // 已确认读不出内嵌标签的文件：补全会随切歌单、媒体变更等入口反复触发，没有这层记忆
    // 就会对同一批文件反复打开。只在进程内驻留——跨启动重读一次的代价有限，
    // 不值得为它再添一份持久化台账。驻留上限按访问序淘汰，只挡住确实在被反复补全的那一批
    private const val MAX_MISSING_ENTRIES = 8_192
    private val knownMissing = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > MAX_MISSING_ENTRIES
    }

    /**
     * 平台是否未读出文件内标签：标题仍等于文件名、艺术家为空或平台/本应用的未知占位值。
     *
     * MediaProvider 对读不到标签的音频按文件名填 TITLE、按 <unknown> 填 ARTIST，
     * 这类曲目的标签很可能写在平台提取器读不到的位置，值得再读一次音频文件本身。
     */
    fun isPlatformTagMissing(
        title: String,
        artist: String,
        fileName: String,
        unknownArtist: String,
    ): Boolean = title == fileName ||
        artist.isBlank() ||
        artist == unknownArtist ||
        artist == MediaStore.UNKNOWN_STRING

    /** 路径扩展名是否属于平台读不出标签的容器 */
    fun isContainerPath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in CONTAINER_EXTENSIONS

    /**
     * 沿用上一轮补出的标签：本次扫描标签仍缺失、旧结果确实补出过标签、且文件自上次扫描以来
     * 未被修改时，直接取旧结果的标题 / 艺术家 / 专辑。
     *
     * 只做字段比较，不读文件，因此可以留在刷新主路径上：刷新是高频操作，
     * 让这条路径免于文件 IO，扫描结果才能立即呈现。
     */
    fun carryOver(track: MusicTrack, cached: MusicTrack?, unknownArtist: String): MusicTrack {
        if (cached == null || cached.fileModifiedMs != track.fileModifiedMs) return track
        val fileName = track.path.substringAfterLast('/').substringBeforeLast('.')
        if (!isPlatformTagMissing(track.title, track.artist, fileName, unknownArtist)) return track
        if (isPlatformTagMissing(cached.title, cached.artist, fileName, unknownArtist)) return track
        return track.copy(title = cached.title, artist = cached.artist, albumName = cached.albumName)
    }

    /**
     * 是否仍需读文件补全：标签缺失、容器在补全范围内，且该文件尚未确认读不出标签。
     * 调用方须在 IO 线程使用：判定含路径解析，逐曲执行。
     */
    fun isPending(track: MusicTrack, unknownArtist: String): Boolean {
        val fileName = track.path.substringAfterLast('/').substringBeforeLast('.')
        if (!isPlatformTagMissing(track.title, track.artist, fileName, unknownArtist)) return false
        if (!isContainerPath(track.path)) return false
        return !isKnownMissing(track)
    }

    /**
     * 读文件补全标签；容器内没有可用的文本标签时返回 null，并记入「已确认无标签」，
     * 使该文件在本次进程内不再被反复打开。调用方须在 IO 线程调用：读取按窗口取文件字节，不做线程切换。
     */
    fun resolve(context: Context, track: MusicTrack): MusicTrack? {
        val embedded = MusicEmbeddedTagReader.read(context, track.path, track.audioUri)
        val title = embedded?.title?.takeIf { it.isNotBlank() }
        val artist = embedded?.artist?.takeIf { it.isNotBlank() }
        val album = embedded?.album?.takeIf { it.isNotBlank() }
        if (title == null && artist == null && album == null) {
            markKnownMissing(track)
            return null
        }
        return track.copy(
            title = title ?: track.title,
            artist = artist ?: track.artist,
            albumName = album ?: track.albumName,
        )
    }

    // 键含文件修改时间：文件被替换或标签被重写后，此前「读不出标签」的结论不再成立
    @Synchronized
    private fun isKnownMissing(track: MusicTrack): Boolean = knownMissing.containsKey(missingKey(track))

    @Synchronized
    private fun markKnownMissing(track: MusicTrack) {
        knownMissing[missingKey(track)] = true
    }

    private fun missingKey(track: MusicTrack): String = track.path + "\u0000" + track.fileModifiedMs
}
