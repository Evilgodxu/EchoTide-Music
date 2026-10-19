package com.yichao.evilgodxu.data.playlist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// 歌手分组曲序存储：歌手分组的曲目由曲库即时解析（见 PlaylistGrouping.artistGroup），
// 顺序本身没有落脚点——用户拖拽调整后按歌手名记一份曲目 id 序列，进页时按它重排，
// 离开再进仍保持。与自定义歌单的曲序同为「顺序即用户数据」，故同样同步写盘（commit）：
// 异步落盘在进程被杀时存在丢失窗口，而这份顺序没有其它可重建的来源。
// 由 App 以单例持有，全库共享同一状态实例
class ArtistOrderStore {
    companion object {
        // 歌手曲序的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
        internal val PERSISTED = PersistedData("music_artist_order_preferences", PersistedFileKind.SHARED_PREFS)
        private const val KEY = "artist_orders"
    }

    // 首次加载标记：多线程首次访问时防止以空表覆盖已持久化数据
    @Volatile
    private var loaded = false
    // 首次读盘的串行标记，避免多线程重复解析
    private val loadMutex = Mutex()
    // 持久化串行执行，避免并发写乱序覆盖
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistMutex = Mutex()
    // 落盘代号：排队期间已有更新的快照提交则跳过本次写入，避免让更旧的顺序最后落盘
    private val persistGeneration = AtomicLong(0)

    // 歌手名 → 该歌手曲目的自定义次序。只记次序不记成员：曲库增删后按 id 查表过滤，
    // 序列里已消失的 id 自然落空，下次拖拽整条覆盖时随之清掉。
    // 歌手改名会留下一条再也命不中的旧条目——改名后重新排序才生成新条目，旧条目无人认领；
    // 残留量以「改过名的歌手数」为界，不值得为它引入一次全库比对来清理
    var orders by mutableStateOf<Map<String, List<Long>>>(emptyMap())
        private set

    // 首次访问时读取持久化数据，避免重复加载。
    // 同步兜底：仅供已确保加载完成的写路径使用；界面侧请改用 awaitLoaded 把读盘放到 IO
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        orders = readOrders(context.applicationContext)
    }

    // 首次读盘切到 IO：整份曲序表的解析不应占用主线程（Compose 副作用内同步读盘会阻塞首帧）
    suspend fun awaitLoaded(context: Context) {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            val cached = withContext(Dispatchers.IO) { readOrders(context.applicationContext) }
            orders = cached
            loaded = true
        }
    }

    fun setOrder(context: Context, artist: String, orderedIds: List<Long>) {
        // 写前先确保已加载，避免以空表覆盖已持久化的其它歌手
        ensureLoaded(context)
        if (artist.isBlank() || orderedIds.isEmpty()) return
        orders = orders + (artist to orderedIds)
        persist(context)
    }

    private fun persist(context: Context) {
        val appContext = context.applicationContext
        val snapshot = orders
        val generation = persistGeneration.incrementAndGet()
        ioScope.launch {
            persistMutex.withLock {
                // 排队期间已产生更新的快照：本次写入会回退用户数据，直接跳过
                if (generation != persistGeneration.get()) return@withLock
                val root = JSONObject()
                snapshot.forEach { (artist, ids) ->
                    root.put(artist, JSONArray(ids.toTypedArray()))
                }
                // 同步写盘：曲序为用户关键数据，apply 异步落盘存在进程被杀丢失窗口
                appContext.getSharedPreferences(PERSISTED.name, Context.MODE_PRIVATE)
                    .edit(commit = true) { putString(KEY, root.toString()) }
            }
        }
    }

    private fun readOrders(context: Context): Map<String, List<Long>> {
        val json = context.getSharedPreferences(PERSISTED.name, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        return try {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { artist ->
                    val ids = root.optJSONArray(artist) ?: return@forEach
                    put(artist, List(ids.length()) { index -> ids.getLong(index) })
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
