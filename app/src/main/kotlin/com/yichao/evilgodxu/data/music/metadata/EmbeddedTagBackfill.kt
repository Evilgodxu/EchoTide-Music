package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.provider.MediaStore
import com.yichao.evilgodxu.data.music.model.MusicTrack

/** 用文件内嵌标签补齐 WAV 等平台读不到的标签 */
internal object EmbeddedTagBackfill {

    /** 平台读不出标签的容器扩展名 */
    private val CONTAINER_EXTENSIONS = setOf("wav")

    // 已确认无标签的文件，进程内记忆，避免反复打开
    private const val MAX_MISSING_ENTRIES = 8_192
    private val knownMissing = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > MAX_MISSING_ENTRIES
    }

    /** 平台是否未读出文件内标签：标题等于文件名或艺术家缺失 */
    fun isPlatformTagMissing(
        title: String,
        artist: String,
        fileName: String,
        unknownArtist: String,
    ): Boolean = title == fileName ||
        artist.isBlank() ||
        artist == unknownArtist ||
        artist == MediaStore.UNKNOWN_STRING

    /** 扩展名是否属于读不出标签的容器 */
    fun isContainerPath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in CONTAINER_EXTENSIONS

    /** 沿用上轮补出的标签：文件未改动且本次仍缺失 */
    fun carryOver(track: MusicTrack, cached: MusicTrack?, unknownArtist: String): MusicTrack {
        if (cached == null || cached.fileModifiedMs != track.fileModifiedMs) return track
        val fileName = track.path.substringAfterLast('/').substringBeforeLast('.')
        if (!isPlatformTagMissing(track.title, track.artist, fileName, unknownArtist)) return track
        if (isPlatformTagMissing(cached.title, cached.artist, fileName, unknownArtist)) return track
        return track.copy(title = cached.title, artist = cached.artist, albumName = cached.albumName)
    }

    /** 是否仍需读文件：标签缺失、容器在范围内且未确认无标签 */
    fun isPending(track: MusicTrack, unknownArtist: String): Boolean {
        val fileName = track.path.substringAfterLast('/').substringBeforeLast('.')
        if (!isPlatformTagMissing(track.title, track.artist, fileName, unknownArtist)) return false
        if (!isContainerPath(track.path)) return false
        return !isKnownMissing(track)
    }

    /** 读文件补全；无可用标签返回 null 并记入已确认无标签 */
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

    // 键含修改时间，文件替换后结论失效
    @Synchronized
    private fun isKnownMissing(track: MusicTrack): Boolean = knownMissing.containsKey(missingKey(track))

    @Synchronized
    private fun markKnownMissing(track: MusicTrack) {
        knownMissing[missingKey(track)] = true
    }

    private fun missingKey(track: MusicTrack): String = track.path + "\u0000" + track.fileModifiedMs
}
