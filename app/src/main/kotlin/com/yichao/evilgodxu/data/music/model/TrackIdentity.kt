package com.yichao.evilgodxu.data.music.model

/**
 * 音轨身份：把「同一首歌在不同平台的两种写法」判为同一首。
 *
 * 各平台对同一首歌的写法不一致，精确文本键在每一类差异上都会把一首歌裂成两条：
 * - 歌手本地化译名：`VALORANT` 与 `无畏契约`；
 * - 合作歌手的连接符与增删：`郑浩/冰洁`、`郑浩&冰洁`，以及 `黄静美` 与 `黄静美&亦瑶`；
 * - 标题的版本后缀（`(feat. …)`、`(日文版)`）—— 由 [normalize] 剥离括号内容处理。
 *
 * 判同规则是「标题归一化后相同 **且** 歌手集合有交集」。只按标题判同会把同名不同曲误并 ——
 * 各平台榜单里同名翻唱很多（实测候选池 313 首中 16 组同名，其中 6 首《祝福祖国》是六个不同歌手的版本）。
 */
internal object TrackIdentity {

    // 合作歌手的连接符：各平台各用各的，须在归一化前切开
    private val ARTIST_SEPARATORS = Regex(
        "[/&、,，;；|]+|\\s+(?:feat\\.?|ft\\.?|with)\\s+",
        RegexOption.IGNORE_CASE,
    )

    private val BRACKETS = Regex("\\([^)]*\\)|（[^）]*）|\\[[^]]*]|【[^】]*】")
    private val PUNCTUATION = Regex("[\\s\\p{Punct}、，。！？·—～]+")

    /** 单条文本的比对形：小写、去括号内容、去标点与空白 */
    fun normalize(value: String): String = value.lowercase()
        .replace(BRACKETS, "")
        .replace(PUNCTUATION, "")
        .trim()

    /** 标题键：归一化后的标题，空串表示无有效标题，此时不参与判同 */
    fun titleKey(title: String): String = normalize(title)

    /** 歌手集合：按连接符切开后逐个归一化，剔除空项 */
    fun artistSet(artist: String): Set<String> = artist.split(ARTIST_SEPARATORS)
        .asSequence()
        .map { normalize(it) }
        .filter { it.isNotEmpty() }
        .toSet()

    /** 是否同一首歌：标题相同且歌手有交集。歌手缺失时一律判为不同曲，宁漏不误并 */
    fun matches(title: String, artist: String, otherTitle: String, otherArtist: String): Boolean {
        val self = titleKey(title)
        if (self.isEmpty() || self != titleKey(otherTitle)) return false
        val selfArtists = artistSet(artist)
        if (selfArtists.isEmpty()) return false
        val otherArtists = artistSet(otherArtist)
        return otherArtists.isNotEmpty() && selfArtists.any { it in otherArtists }
    }
}

/**
 * 按音轨身份去重：同标题桶内歌手有交集即视为同曲，只保留先出现的条目。
 *
 * 保留哪一条由调用方的列表顺序决定 —— 平台顺序靠前的元数据更完整，故由调用方排列。
 * 歌手缺失的条目不参与判同（既不被合并，也不与后续条目合并），宁漏不误并。
 */
internal fun <T> List<T>.distinctByTrack(title: (T) -> String, artist: (T) -> String): List<T> {
    val kept = mutableListOf<T>()
    val byTitle = HashMap<String, MutableList<Pair<String, String>>>()
    forEach { item ->
        val itemTitle = title(item)
        val itemArtist = artist(item)
        val titleKey = TrackIdentity.titleKey(itemTitle)
        if (titleKey.isEmpty()) return@forEach
        val bucket = byTitle.getOrPut(titleKey) { mutableListOf() }
        if (bucket.none { (keptTitle, keptArtist) ->
                TrackIdentity.matches(itemTitle, itemArtist, keptTitle, keptArtist)
            }
        ) {
            bucket += itemTitle to itemArtist
            kept += item
        }
    }
    return kept
}

/**
 * 曲库索引：按标题键分桶，判同只需与同标题的条目比较，避免每首候选遍历整个曲库。
 *
 * [exactKeys] 是「标题|歌手」精确键，作为 O(1) 快路径 —— 绝大多数命中都在这一层结束。
 */
internal class OwnedTrackIndex(tracks: List<MusicTrack>) {

    private val exactKeys: Set<String>

    private val byTitle: Map<String, List<Pair<String, String>>>

    init {
        val keys = HashSet<String>(tracks.size)
        val buckets = HashMap<String, MutableList<Pair<String, String>>>()
        tracks.forEach { track ->
            val titleKey = TrackIdentity.titleKey(track.title)
            if (titleKey.isEmpty()) return@forEach
            keys += "$titleKey|${TrackIdentity.normalize(track.artist)}"
            buckets.getOrPut(titleKey) { mutableListOf() } += track.title to track.artist
        }
        exactKeys = keys
        byTitle = buckets
    }

    /** 该曲目是否已存在于曲库 */
    fun contains(title: String, artist: String): Boolean {
        val titleKey = TrackIdentity.titleKey(title)
        if (titleKey.isEmpty()) return false
        if ("$titleKey|${TrackIdentity.normalize(artist)}" in exactKeys) return true
        val bucket = byTitle[titleKey] ?: return false
        return bucket.any { (existingTitle, existingArtist) ->
            TrackIdentity.matches(title, artist, existingTitle, existingArtist)
        }
    }
}
