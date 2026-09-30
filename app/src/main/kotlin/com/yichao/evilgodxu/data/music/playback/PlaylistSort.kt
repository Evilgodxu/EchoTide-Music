package com.yichao.evilgodxu.data.music.playback

import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.text.Collator
import java.util.Locale

// 播放列表排序字段：默认顺序 / 收藏 / 文件修改时间 / 标题 / 歌手 / 专辑 / 时长
enum class PlaylistSortField {
    DEFAULT,
    FAVORITE,
    MODIFIED_TIME,
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
}

// 按字段排序播放列表；descending 为逆序。
// 标题/歌手/专辑以及默认顺序中的文本比较统一采用首字母自然序：
// 系统 Collator 对中文按拼音、英文按字母排序，首字符为数字（含中文数字）者统一靠前
internal fun sortTracks(
    tracks: List<MusicTrack>,
    field: PlaylistSortField,
    descending: Boolean,
): List<MusicTrack> {
    val ordered = when (field) {
        PlaylistSortField.DEFAULT -> tracks.sortedByDefaultOrder()
        PlaylistSortField.FAVORITE ->
            // 收藏：已收藏者靠前，同一收藏态内仍按标题自然序
            tracks.sortedWith(
                compareByDescending<MusicTrack> { it.isFavorite }
                    .then(naturalStringComparator<MusicTrack> { it.title })
            )
        PlaylistSortField.MODIFIED_TIME ->
            // 新旧：仅按文件修改时间排序（新在前），不附加任何其它排序机制
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

// 首字母自然序字符串比较器：数字开头排前且按数值升序，其余按中文拼音/英文字母排序。
// Collator 非线程安全，按比较器实例创建，单次排序内独占使用
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

// 取首个字符的数值：阿拉伯数字取连续数字串，中文数字取单字数值；非数字开头返回 null
private fun leadingNumberValue(text: String): Long? {
    val trimmed = text.trimStart()
    if (trimmed.isEmpty()) return null
    val first = trimmed[0]
    if (first.isDigit()) return trimmed.takeWhile { it.isDigit() }.toLongOrNull()
    return CHINESE_NUMERALS[first]
}

// 中文数字字符数值表（含大写与异体），用于数字开头判定
private val CHINESE_NUMERALS: Map<Char, Long> = mapOf(
    '〇' to 0L, '零' to 0L,
    '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L, '四' to 4L,
    '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L,
    '十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10000L, '亿' to 100000000L,
)

// 多歌手分隔符：兼容常见分隔形式（顿号/逗号/分号/斜杠/反斜杠/连接符&）
private val ARTIST_SEPARATOR = Regex("""[、,，;；/\\&]""")

// 解析曲目的全部歌手名：按多歌手分隔符拆分、去空白、去空；无分隔符时视为单一歌手
internal fun parseTrackArtists(artist: String): List<String> =
    artist.split(ARTIST_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .ifEmpty { listOf(artist.trim()) }

// 按默认规则排序：固定标题自然序首位歌曲为锚点，其下分两趟聚拢而非一次性比较，
// 避免「标题优先」与「歌手/专辑相邻」互相抵触。
// 锚点：标题自然序（数字优先）升序的首位歌曲，位置固定不随后续排序改变；
// 归属：多歌手曲目归属到曲库中曲目数最多的歌手（数量相同取解析顺序靠前者），避免把多个歌手块并入一块；
// 第 2 趟（二级）：在锚点之下按归属歌手聚拢同歌手曲目，归属歌手在锚点下 ≥2 首才真正成块；
// 第 3 趟（三级）：在锚点之下对未被歌手聚拢的曲目（归属歌手仅 1 首）按专辑聚拢，
//                 同专辑可跨歌手相邻；已由二级（同歌手）占用的曲目不再参与。
private fun List<MusicTrack>.sortedByDefaultOrder(): List<MusicTrack> {
    val titleSorted = sortedWith(naturalStringComparator<MusicTrack> { it.title })
    if (titleSorted.isEmpty()) return titleSorted
    val anchor = titleSorted.first()
    val rest = titleSorted.drop(1)

    // 统计曲库（整个待排序列表）中每位歌手的曲目数，用于多歌手曲目的归属判定
    val artistSongCounts = HashMap<String, Int>()
    forEach { track ->
        parseTrackArtists(track.artist).forEach { artist ->
            artistSongCounts[artist] = (artistSongCounts[artist] ?: 0) + 1
        }
    }

    // 归属歌手：多歌手时取曲库曲目数最多者；数量相同取解析顺序靠前者
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

    // 统计锚点之下各归属歌手的曲目数，据此决定每首曲目归入「歌手块」还是「专辑块」
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

// 专辑聚拢键：专辑名 + 专辑 id，避免同名不同专辑被并入同一块
private fun albumGroupKey(track: MusicTrack): String = "${track.albumName}\u0000${track.albumId}"

// 稳定分组：相同键归为连续一组，组的先后由首次出现顺序决定，组内保持原顺序
private fun <T> List<T>.stableGroups(key: (T) -> String): List<List<T>> {
    val groups = LinkedHashMap<String, MutableList<T>>()
    forEach { item -> groups.getOrPut(key(item)) { mutableListOf() }.add(item) }
    return groups.values.toList()
}
