package com.yichao.evilgodxu.data.music.recommend

import android.content.Context
import com.yichao.evilgodxu.data.music.api.KugouMusicApi
import com.yichao.evilgodxu.data.music.api.KuwoMusicApi
import com.yichao.evilgodxu.data.music.api.MiguMusicApi
import com.yichao.evilgodxu.data.music.api.NeteaseMusicApi
import com.yichao.evilgodxu.data.music.api.QQMusicApi
import com.yichao.evilgodxu.data.music.api.sourceOf
import com.yichao.evilgodxu.data.music.blacklist.BlacklistStore
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 榜单候选池：各平台当期榜单连同候选歌词，按周刷新后落盘。
 *
 * 榜单以周为单位更新，而候选歌词是推荐流程中请求量最大的一环 ——
 * 若每次生成推荐都重新联网，一次刷新或一轮切歌就会把整池歌词重拉一遍。
 * 故候选池在此收敛「周更 + 落盘」：周内任何一次推荐计算都只读落盘结果，不再产生网络请求。
 *
 * 规模口径：各平台统一取榜单前 [CHART_LIMIT] 首（四家合计约四百首）。榜单容量虽不一致
 * （网易 100 / QQ 300 / 酷狗 500 / 酷我 300），但统一口径更划算 —— 取满全量会把周更刷新
 * 拉成上千次歌词请求，而榜单尾部本就是长尾，收益不抵耗时。
 *
 * 候选池只负责「该平台当期有哪些可用的新歌」，不参与本地曲库与黑名单的过滤，
 * 后者随用户操作随时变化，需在每次计算推荐时现场判定，不能被固化进周更的快照。
 */
internal object ChartPool {

    private const val FILE_NAME = "recommend_chart_pool.json"
    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    // 各平台统一取榜单前 100 首：榜单容量不一（实测网易 100 / QQ 300 / 酷狗 500 / 酷我 300），
    // 取满全量会把周更刷新拉成上千次歌词请求，且榜单尾部本就是长尾，收益不抵耗时
    private const val CHART_LIMIT = 100

    // 歌词不足的行数视为纯音乐/冷门曲，直接丢弃而非给低分
    private const val MIN_LYRIC_LINES = 5

    // 并发拉取歌词的批大小：控制瞬时请求数，避免触发平台风控
    private const val LYRIC_BATCH = 6

    /**
     * 取候选池快照。周内直接读落盘结果；跨周或 [force] 时重新抓取并覆盖。
     *
     * 抓取失败（接口变更、风控、断网）时沿用上一次的候选池：一次失败不该让推荐空到下个周更。
     * 快照带回抓取时刻 —— 每日推荐的轮换天数以它为起点，刷新即回到排序榜首。
     */
    suspend fun snapshot(context: Context, force: Boolean = false): ChartPoolSnapshot =
        withContext(Dispatchers.IO) {
            val cached = read(context)
            if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < REFRESH_INTERVAL_MS) {
                return@withContext cached
            }
            val fresh = fetch(context)
            // 本轮流为空（四家全挂）而磁盘上还有旧池时保留旧池，但抓取时刻不更新：
            // 轮换天数继续按旧池起点累计，不会因一次失败假装刷新过
            if (fresh.isEmpty()) return@withContext cached ?: ChartPoolSnapshot(0L, emptyList())
            val snapshot = ChartPoolSnapshot(System.currentTimeMillis(), fresh)
            write(context, snapshot)
            snapshot
        }

    /** 抓取各平台榜单并补齐歌词，返回本次可用的候选 */
    private suspend fun fetch(context: Context): List<ChartCandidate> {
        val charts = coroutineScope {
            MusicSearchSource.entries.map { source ->
                async { runCatching { sourceOf(source).chart(CHART_LIMIT) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        val results = charts.flatten()
            .distinctBy { BlacklistStore.keyOf(it.title, it.artist) }
            .filter { BlacklistStore.keyOf(it.title, it.artist).isNotEmpty() }

        val candidates = mutableListOf<ChartCandidate>()
        results.chunked(LYRIC_BATCH).forEach { batch ->
            val fetched = coroutineScope {
                batch.map { result -> async { result to fetchLyricLines(context, result) } }.awaitAll()
            }
            fetched.forEach { (result, rawLines) ->
                val lines = LyricFeatures.cleanLyrics(rawLines)
                if (lines.size >= MIN_LYRIC_LINES) candidates += ChartCandidate(result, lines)
            }
        }
        return candidates
    }

    /** 拉取候选歌词：代理音源优先，未配置时回退各平台内置歌词接口 */
    private suspend fun fetchLyricLines(
        context: Context,
        result: NeteaseSongSearchResult,
    ): List<String> = try {
        val lines = ProxySourceEngine.lyricLines(context, result.source, result)
            ?: when (result.source) {
                MusicSearchSource.NETEASE -> NeteaseMusicApi.lyric(result.id).lines
                MusicSearchSource.QQ -> QQMusicApi.lyricLines(result).orEmpty()
                MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(result).orEmpty()
                MusicSearchSource.KUWO -> KuwoMusicApi.lyricLines(result).orEmpty()
                MusicSearchSource.MIGU -> MiguMusicApi.lyricLines(result).orEmpty()
            }
        lines.map { it.text }
    } catch (e: Exception) {
        CrashLogManager.logException("ChartPool", "拉取候选歌词失败: ${result.title}", e)
        emptyList()
    }

    private fun read(context: Context): ChartPoolSnapshot? = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            null
        } else {
            val root = JSONObject(file.readText())
            val items = root.optJSONArray("items") ?: JSONArray()
            ChartPoolSnapshot(
                fetchedAt = root.optLong("fetchedAt"),
                items = List(items.length()) { index -> itemFrom(items.getJSONObject(index)) },
            )
        }
    } catch (e: Exception) {
        // 快照结构损坏时按无缓存处理：下一轮重新抓取即可恢复，不做修复尝试
        CrashLogManager.logException("ChartPool", "读取候选池失败", e)
        null
    }

    private fun write(context: Context, snapshot: ChartPoolSnapshot) = try {
        val array = JSONArray()
        snapshot.items.forEach { array.put(itemTo(it)) }
        val root = JSONObject()
            .put("fetchedAt", snapshot.fetchedAt)
            .put("items", array)
        File(context.filesDir, FILE_NAME).writeText(root.toString())
    } catch (e: Exception) {
        CrashLogManager.logException("ChartPool", "写入候选池失败", e)
    }

    private fun itemTo(candidate: ChartCandidate): JSONObject = JSONObject().apply {
        val result = candidate.result
        put("id", result.id)
        put("title", result.title)
        put("artist", result.artist)
        put("coverUrl", result.coverUrl ?: "")
        put("coverThumbUrl", result.coverThumbUrl ?: "")
        put("coverId", result.coverId ?: "")
        put("duration", result.duration)
        put("source", result.source.name)
        put("sourceId", result.sourceId ?: "")
        put("lines", JSONArray(candidate.lines))
    }

    private fun itemFrom(json: JSONObject): ChartCandidate {
        val source = runCatching { MusicSearchSource.valueOf(json.optString("source")) }
            .getOrDefault(MusicSearchSource.NETEASE)
        val lines = json.optJSONArray("lines") ?: JSONArray()
        val result = NeteaseSongSearchResult(
            id = json.optLong("id"),
            title = json.optString("title"),
            artist = json.optString("artist"),
            coverUrl = json.optString("coverUrl").takeIf { it.isNotBlank() },
            coverThumbUrl = json.optString("coverThumbUrl").takeIf { it.isNotBlank() },
            coverId = json.optString("coverId").takeIf { it.isNotBlank() },
            duration = json.optLong("duration"),
            source = source,
            sourceId = json.optString("sourceId").takeIf { it.isNotBlank() },
        )
        return ChartCandidate(result, List(lines.length()) { lines.optString(it) })
    }

}

/**
 * 候选池快照。
 *
 * [fetchedAt] 是本期候选的抓取时刻，同时充当每日推荐轮换的起点：候选池刷新即排序回到榜首。
 */
internal data class ChartPoolSnapshot(
    val fetchedAt: Long,
    val items: List<ChartCandidate>,
)

/** 候选池中的一首歌：榜单条目 + 已清洗的歌词行 */
internal data class ChartCandidate(
    val result: NeteaseSongSearchResult,
    val lines: List<String>,
)
