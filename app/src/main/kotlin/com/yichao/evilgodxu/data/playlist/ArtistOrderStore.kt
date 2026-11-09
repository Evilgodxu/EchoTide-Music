package com.yichao.evilgodxu.data.playlist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.yichao.evilgodxu.data.persistence.JsonSerializer
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.catchPersistenceIo
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 歌手曲序的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
internal val ARTIST_ORDER_STORE = PersistedData("artist_orders", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class ArtistOrderArchive(
    val orders: Map<String, List<Long>> = emptyMap(),
)

private val Context.artistOrderDataStore: DataStore<ArtistOrderArchive> by dataStore(
    fileName = "${ARTIST_ORDER_STORE.name}.json",
    serializer = JsonSerializer(ArtistOrderArchive.serializer(), ArtistOrderArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("ArtistOrderStore", "歌手曲序数据损坏，已重置为空", throwable)
        ArtistOrderArchive()
    },
)

/**
 * 歌手分组曲序存储。
 *
 * 歌手分组的曲目由曲库即时解析（见 PlaylistGrouping.artistGroup），顺序本身没有落脚点——
 * 用户拖拽调整后按歌手名记一份曲目 id 序列，进页时按它重排。DataStore 是唯一事实源，
 * 写入整份表一次提交，读取由 [orders] 的收集侧持续回流。
 */
class ArtistOrderStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    // 歌手名 → 该歌手曲目的自定义次序。只记次序不记成员：曲库增删后按 id 查表过滤，
    // 序列里已消失的 id 自然落空，下次拖拽整条覆盖时随之清掉。
    // 歌手改名会留下一条再也命不中的旧条目——改名后重新排序才生成新条目，旧条目无人认领；
    // 残留量以「改过名的歌手数」为界，不值得为它引入一次全库比对来清理
    var orders by mutableStateOf<Map<String, List<Long>>>(emptyMap())
        private set

    init {
        scope.launch {
            appContext.artistOrderDataStore.data
                .catchPersistenceIo("ArtistOrderStore") { ArtistOrderArchive() }
                .collect { archive ->
                    orders = archive.orders
                }
        }
    }

    suspend fun setOrder(artist: String, orderedIds: List<Long>) {
        if (artist.isBlank() || orderedIds.isEmpty()) return
        appContext.artistOrderDataStore.updateData { archive ->
            archive.copy(orders = archive.orders + (artist to orderedIds))
        }
    }
}
