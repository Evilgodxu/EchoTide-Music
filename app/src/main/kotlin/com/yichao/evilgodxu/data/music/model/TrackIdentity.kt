package com.yichao.evilgodxu.data.music.model

// 音轨身份：把同一首歌在不同平台的写法判为同一首
// 平台差异在歌手译名、合作连接符与标题版本后缀
// 判同规则：标题归一化相同且歌手集合有交集
internal object TrackIdentity {

    // 合作歌手连接符，归一化前切开
    private val ARTIST_SEPARATORS = Regex(
        "[/&、,，;；|]+|\\s+(?:feat\\.?|ft\\.?|with)\\s+",
        RegexOption.IGNORE_CASE,
    )

    private val BRACKETS = Regex("\\([^)]*\\)|（[^）]*）|\\[[^]]*]|【[^】]*】")
    private val PUNCTUATION = Regex("[\\s\\p{Punct}、，。！？·—～]+")

    // 比对形：小写、去括号、去标点空白
    fun normalize(value: String): String = value.lowercase()
        .replace(BRACKETS, "")
        .replace(PUNCTUATION, "")
        .trim()

    // 标题键；空串表示无有效标题，不参与判同
    fun titleKey(title: String): String = normalize(title)

    // 歌手集合：切开归一化，剔除空项
    fun artistSet(artist: String): Set<String> = artist.split(ARTIST_SEPARATORS)
        .asSequence()
        .map { normalize(it) }
        .filter { it.isNotEmpty() }
        .toSet()

    // 标题相同且歌手有交集；歌手缺失判不同，宁漏勿并
    fun matches(title: String, artist: String, otherTitle: String, otherArtist: String): Boolean {
        val self = titleKey(title)
        if (self.isEmpty() || self != titleKey(otherTitle)) return false
        val selfArtists = artistSet(artist)
        if (selfArtists.isEmpty()) return false
        val otherArtists = artistSet(otherArtist)
        return otherArtists.isNotEmpty() && selfArtists.any { it in otherArtists }
    }
}

// 按音轨身份去重，同标题桶内歌手有交集即同曲
// 保留先出现条目，顺序由调用方决定；缺歌手不判同
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

// 曲库索引：按标题键分桶，只与同标题条目比较
// exactKeys 精确键作 O(1) 快路径
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

    // 该曲目是否已存在于曲库
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
