package com.yichao.evilgodxu.data.music.recommend

import android.content.Context
import com.yichao.evilgodxu.data.music.api.KugouMusicApi
import com.yichao.evilgodxu.data.music.api.KuwoMusicApi
import com.yichao.evilgodxu.data.music.api.MiguMusicApi
import com.yichao.evilgodxu.data.music.api.NeteaseMusicApi
import com.yichao.evilgodxu.data.music.api.QQMusicApi
import com.yichao.evilgodxu.data.music.api.builtInSourceOf
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.distinctByTrack
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 榜单候选池：各平台当期榜单连同候选歌词，按日刷新后落盘。
 *
 * 每日推荐要求当日结果与昨日不同，而候选池不换血、排序就只会重算出同一份次序 ——
 * 差异只能来自候选本身，故换期定为日更。候选歌词是推荐流程中请求量最大的一环，
 * 若每次生成推荐都重新联网，一次换期或一轮切歌就会把整池歌词重拉一遍；
 * 故候选池在此收敛「日更 + 落盘」：当日任何一次推荐计算都只读落盘结果，不再产生网络请求。
 *
 * 换期时刻定在每日 11:00（北京时间，见 [refreshTime]），不与实际抓取时刻挂钩。
 * 换期后不必等应用恰好在换期时刻运行：[refreshIfOutdated] 供启动时预热，生成推荐时亦会按刻度判定，
 * 晚于换期时刻启动、或进程跨过换期时刻后继续使用，都会用上新一期榜单。
 *
 * 规模口径：各平台统一取榜单前 [CHART_LIMIT] 首（四家合计约四百首）。榜单容量虽不一致
 * （网易 100 / QQ 300 / 酷狗 500 / 酷我 300），但统一口径更划算 —— 取满全量会把日更刷新
 * 拉成上千次歌词请求，而榜单尾部本就是长尾，收益不抵耗时。
 *
 * 候选池只负责「该平台当期有哪些可用的新歌」，不参与本地曲库与黑名单的过滤，
 * 后者随用户操作随时变化，需在每次计算推荐时现场判定，不能被固化进日更的快照。
 */
internal object ChartPool {

    private const val FILE_NAME = "recommend_chart_pool.json"

    /**
     * 每日推荐的时间基准：换期刻度按北京时间判定。
     *
     * 取固定时区而非设备本地时区 —— 要对齐的是国内平台按北京时间换榜的节奏，
     * 设备时区变化不该让换期点跟着漂。
     */
    private val TIME_ZONE = ZoneId.of("Asia/Shanghai")

    // 换期刻度：每日 11:00 起进入新一期，此后启动预热或生成推荐时重抓整池。
    // 不在零点换期：当日榜单尚未更新完，零点抓到的仍大幅是前一天的榜
    private const val REFRESH_HOUR = 11

    // 各平台统一取榜单前 100 首：榜单容量不一（实测网易 100 / QQ 300 / 酷狗 500 / 酷我 300），
    // 取满全量会把日更刷新拉成上千次歌词请求，且榜单尾部本就是长尾，收益不抵耗时
    private const val CHART_LIMIT = 100

    // 歌词不足的行数视为纯音乐/冷门曲，直接丢弃而非给低分
    private const val MIN_LYRIC_LINES = 5

    // 并发拉取歌词的批大小：控制瞬时请求数，避免触发平台风控
    private const val LYRIC_BATCH = 6

    // 换期重抓互斥：启动预热与生成推荐是两个独立触发点，同时到达时只应抓一次
    private val refreshMutex = Mutex()

    // 旧快照以平台枚举名持久化，读取时映射回平台键以沿用上一期的落盘结果
    private val LEGACY_PLATFORM_NAMES = mapOf(
        "NETEASE" to MusicSearchSource.NETEASE.key,
        "QQ" to MusicSearchSource.QQ.key,
        "KUGOU" to MusicSearchSource.KUGOU.key,
        "KUWO" to MusicSearchSource.KUWO.key,
        "MIGU" to MusicSearchSource.MIGU.key,
    )

    /**
     * 取候选池快照。
     *
     * @param refresh 是否允许在快照缺失或跨过换期刻度时联网重抓。收藏、曲库入库等高频重算传 false，
     *   只读本地落盘结果 —— 用户点一次收藏不该触发整池歌词的重新拉取。
     *
     * 抓取失败（接口变更、风控、断网）时沿用上一次的候选池：一次失败不该让推荐空到下个周更。
     * 快照带回抓取时刻 —— 每日推荐的轮换天数以它为起点，刷新即回到排序榜首。
     */
    suspend fun snapshot(context: Context, refresh: Boolean): ChartPoolSnapshot =
        withContext(Dispatchers.IO) {
            if (!refresh) return@withContext read(context) ?: ChartPoolSnapshot(0L, emptyList())
            refreshMutex.withLock {
                val cached = read(context)
                if (cached != null && !isOutdated(cached.fetchedAt)) return@withLock cached
                refresh(context, cached)
            }
        }

    /**
     * 启动预热：已跨换期刻度时按需重抓。
     *
     * 只有本机已存在候选池（用户用过每日推荐）才预热 —— 从未生成过推荐的用户不该为一次启动
     * 付整池抓取的代价。
     *
     * @return 本次落盘后的快照；未发生抓取（本机没有候选池、或仍在本期）时返回 null
     */
    suspend fun refreshIfOutdated(context: Context): ChartPoolSnapshot? =
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val cached = read(context) ?: return@withLock null
                if (!isOutdated(cached.fetchedAt)) return@withLock null
                refresh(context, cached)
            }
        }

    /** 候选池是否已跨过换期刻度。启动预热与生成推荐据此判断是否需要联网重抓 */
    fun isOutdated(fetchedAt: Long): Boolean = fetchedAt < refreshTime(System.currentTimeMillis())

    /**
     * 重抓整池并落盘。
     *
     * 本轮流为空（四家全挂）而磁盘上还有旧池时保留旧池，且抓取时刻不更新：轮换天数继续按
     * 旧池起点累计，不会因一次失败假装刷新过。
     */
    private suspend fun refresh(context: Context, cached: ChartPoolSnapshot?): ChartPoolSnapshot {
        val fresh = fetch(context)
        if (fresh.isEmpty()) return cached ?: ChartPoolSnapshot(0L, emptyList())
        val snapshot = ChartPoolSnapshot(System.currentTimeMillis(), fresh)
        write(context, snapshot)
        return snapshot
    }

    /**
     * 当前所处的换期刻度：最近一次已到达的 11:00（北京时间）。
     *
     * 判据取时间轴上的固定刻度，而非「距上次抓取满 24 小时」：按间隔计时会让换期点随每次实际
     * 抓取时刻向后漂移，几轮之后与 11:00 脱钩；固定刻度下抓取失败也不推后换期。
     */
    private fun refreshTime(now: Long): Long {
        val zone = TIME_ZONE
        val today = Instant.ofEpochMilli(now).atZone(zone)
            .toLocalDate()
            .atTime(REFRESH_HOUR, 0)
            .atZone(zone)
        // 当日 11:00 尚未到时，当日刻度还没到，当前刻度仍是前一天
        val boundary = if (today.toInstant().toEpochMilli() > now) today.minusDays(1) else today
        return boundary.toInstant().toEpochMilli()
    }

    /** 抓取各平台榜单并补齐歌词，返回本次可用的候选 */
    private suspend fun fetch(context: Context): List<ChartCandidate> {
        val charts = coroutineScope {
            // 榜单只取内置平台：自定义平台没有内置实现，规范也未定义榜单动作
            MusicSearchSource.BUILT_IN.mapNotNull { builtInSourceOf(it) }
                .map { source -> async { runCatching { source.chart(CHART_LIMIT) }.getOrDefault(emptyList()) } }
                .awaitAll()
        }
        // 跨平台去重按音轨身份而非精确文本键：各平台榜单大量交集，且同一首歌的写法不一致
        // （译名 `VALORANT` / `无畏契约`、合作歌手连接符不同），精确键去不掉，
        // 同一首歌会占掉多个候选位
        val results = charts.flatten().distinctByTrack({ it.title }, { it.artist })

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
                else -> emptyList()
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

    // 全量快照写盘：先写中转文件再改名，更新期间读池的重算不会读到半截 JSON
    private fun write(context: Context, snapshot: ChartPoolSnapshot) {
        try {
            val array = JSONArray()
            snapshot.items.forEach { array.put(itemTo(it)) }
            val root = JSONObject()
                .put("fetchedAt", snapshot.fetchedAt)
                .put("items", array)
            val file = File(context.filesDir, FILE_NAME)
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            val content = root.toString()
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                temp.delete()
                file.writeText(content)
            }
        } catch (e: Exception) {
            CrashLogManager.logException("ChartPool", "写入候选池失败", e)
        }
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
        put("source", result.source.key)
        put("sourceId", result.sourceId ?: "")
        put("lines", JSONArray(candidate.lines))
    }

    private fun itemFrom(json: JSONObject): ChartCandidate {
        // 旧快照以平台枚举名持久化，先经旧名映射回平台键；两者都认不出时按网易云处理
        val stored = json.optString("source")
        val source = MusicSearchSource(LEGACY_PLATFORM_NAMES[stored] ?: stored)
            .takeIf { it.key.isNotBlank() } ?: MusicSearchSource.NETEASE
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
 * [fetchedAt] 是本期候选的抓取时刻，同时充当换期判定的基准：它与最近一次换期刻度比较，
 * 即可判断本次排序依据的是否为当期候选。
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
