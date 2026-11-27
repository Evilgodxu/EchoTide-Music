package com.yichao.evilgodxu.data.music.playback

import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.text.Collator
import java.util.Locale

// 排序字段：默认、收藏、修改时间、标题、歌手等
enum class PlaylistSortField {
    DEFAULT,
    FAVORITE,
    MODIFIED_TIME,
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
}

// 按字段排序，descending 逆序；文本用自然序
internal fun sortTracks(
    tracks: List<MusicTrack>,
    field: PlaylistSortField,
    descending: Boolean,
): List<MusicTrack> {
    val ordered = when (field) {
        PlaylistSortField.DEFAULT -> tracks.sortedByDefaultOrder()
        PlaylistSortField.FAVORITE ->
            // 收藏靠前，同态内按标题自然序
            tracks.sortedWith(
                compareByDescending<MusicTrack> { it.isFavorite }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
        PlaylistSortField.MODIFIED_TIME ->
            // 仅按修改时间，新在前
            tracks.sortedWith(compareByDescending<MusicTrack> { it.fileModifiedMs })
        PlaylistSortField.TITLE ->
            tracks.sortedWith(naturalStringComparator<MusicTrack> { it.title })
        PlaylistSortField.ARTIST ->
            tracks.sortedWith(
                naturalStringComparator<MusicTrack> { it.artist }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
        PlaylistSortField.ALBUM ->
            tracks.sortedWith(
                naturalStringComparator<MusicTrack> { it.albumName }
                    .thenBy { it.albumId }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
        PlaylistSortField.DURATION ->
            tracks.sortedWith(
                compareBy<MusicTrack> { it.duration }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
    }
    return if (descending) ordered.reversed() else ordered
}

// 首字母自然序：数字开头按数值在前，其余按拼音/字母
// Collator 非线程安全，单次排序内独占使用
private fun <T> naturalStringComparator(selector: (T) -> String): Comparator<T> {
    val collator = Collator.getInstance(Locale.CHINA)
    return Comparator { a, b ->
        val aText = selector(a)
        val bText = selector(b)
        val aNumber = leadingNumberValue(aText)
        val bNumber = leadingNumberValue(bText)
        val byNumber = when {
            aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
            aNumber != null -> -1
            bNumber != null -> 1
            else -> 0
        }
        if (byNumber != 0) byNumber else collator.compare(aText, bText)
    }
}

// 取开头数字值；非数字开头为 null
private fun leadingNumberValue(text: String): Long? {
    val trimmed = text.trimStart()
    if (trimmed.isEmpty()) return null
    val first = trimmed[0]
    if (first.isDigit()) return trimmed.takeWhile { it.isDigit() }.toLongOrNull()
    return CHINESE_NUMERALS[first]
}

// 中文数字字符数值表
private val CHINESE_NUMERALS: Map<Char, Long> = mapOf(
    '〇' to 0L, '零' to 0L,
    '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L, '四' to 4L,
    '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L,
    '十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10000L, '亿' to 100000000L,
)

// 多歌手分隔符：顿号、逗号、分号、斜杠、&
private val ARTIST_SEPARATOR = Regex("""[、,，;；/\\&]""")

// 拆分歌手名，空则回退原串
internal fun parseTrackArtists(artist: String): List<String> =
    artist.split(ARTIST_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(artist.trim()) }

// 默认排序：锚点固定，其下先聚歌手块再聚专辑块
// 锚点为标题首位；多歌手归曲目最多者
private fun List<MusicTrack>.sortedByDefaultOrder(): List<MusicTrack> {
    val titleSorted = sortedWith(naturalStringComparator<MusicTrack> { it.title })
    if (titleSorted.isEmpty()) return titleSorted
    val anchor = titleSorted.first()
    val rest = titleSorted.drop(1)

    // 统计各歌手曲目数，用于多歌手归属
    val artistSongCounts = HashMap<String, Int>()
    forEach { track ->
        parseTrackArtists(track.artist).forEach { artist ->
            artistSongCounts[artist] = (artistSongCounts[artist] ?: 0) + 1
        }
    }

    // 归属歌手取曲库中曲目最多者，同数取靠前
    fun ownerOf(track: MusicTrack): String {
        val artists = parseTrackArtists(track.artist)
        var owner = artists.first()
        var ownerCount = artistSongCounts[owner] ?: 0
        for (candidate in artists.drop(1)) {
            val candidateCount = artistSongCounts[candidate] ?: 0
            if (candidateCount > ownerCount) {
                owner = candidate
                ownerCount = candidateCount
            }
        }
        return owner
    }

    // 统计锚点下各归属歌手曲目数
    val ownerCountBelowAnchor = HashMap<String, Int>()
    rest.forEach { track ->
        val owner = ownerOf(track)
        ownerCountBelowAnchor[owner] = (ownerCountBelowAnchor[owner] ?: 0) + 1
    }

    val blockKeyByTrackId = HashMap<Long, String>()
    rest.forEach { track ->
        val owner = ownerOf(track)
        blockKeyByTrackId[track.id] = if ((ownerCountBelowAnchor[owner] ?: 0) >= 2) {
            "A\u0000$owner"
        } else {
            "L\u0000${albumGroupKey(track)}"
        }
    }
    return listOf(anchor) + rest.stableGroups { track -> blockKeyByTrackId.getValue(track.id) }.flatten()
}

// 专辑块键：专辑名 + 专辑 id
private fun albumGroupKey(track: MusicTrack): String = "${track.albumName}\u0000${track.albumId}"

// 稳定分组：组序按首现，组内保序
private fun <T> List<T>.stableGroups(key: (T) -> String): List<List<T>> {
    val groups = LinkedHashMap<String, MutableList<T>>()
    forEach { item -> groups.getOrPut(key(item)) { mutableListOf() }.add(item) }
    return groups.values.toList()
}
