package com.yichao.evilgodxu.data.music.model

// 在线音源匹配候选：元数据补全时按候选匹配当前曲目
internal data class NeteaseSongMatch(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String?
)

// 在线搜索结果的曲目标识在写入播放列表时统一加该偏移，与本地曲目 ID 分属不同空间避免碰撞
private const val ONLINE_TRACK_ID_OFFSET = 1_000_000L

// 在线结果对应的播放列表曲目 ID
internal val NeteaseSongSearchResult.playlistTrackId: Long
    get() = id + ONLINE_TRACK_ID_OFFSET

// 在线音乐搜索来源
enum class MusicSearchSource { NETEASE, QQ, KUGOU, KUWO, MIGU }

// 在线搜索结果
data class NeteaseSongSearchResult(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    /** CDN 缩略图 URL（封面 + ?param=128y128），列表行使用以加快加载 */
    val coverThumbUrl: String? = null,
    val duration: Long = 0L,
    val source: MusicSearchSource = MusicSearchSource.NETEASE,
    /** 平台内歌曲标识（QQ 的 songmid、酷狗的 hash），取播放地址/歌词时使用 */
    val sourceId: String? = null,
    /** 封面 ID：代理音源搜索结果仅有封面 ID 时，播放时经 pic 动作换取真实地址 */
    val coverId: String? = null,
)

// 内置歌单解析结果：歌单名称 + 歌曲列表
internal data class NeteasePlaylistData(
    val name: String,
    val songs: List<NeteaseSongSearchResult>,
)

// 在线歌词数据
internal data class NeteaseLyricData(val lines: List<LyricLine>)

// 逐字歌词
data class LyricWord(val startMs: Long, val durationMs: Long, val text: String)

// 歌词行
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    /** 中文翻译（在线歌词接口的 tlyric/trans 字段按时间戳合并后写入） */
    val translation: String? = null
)
