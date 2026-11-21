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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 收藏曲序的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
internal val FAVORITE_ORDER_STORE = PersistedData("favorite_orders", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class FavoriteOrderArchive(
    val order: List<Long> = emptyList(),
)

private val Context.favoriteOrderDataStore: DataStore<FavoriteOrderArchive> by dataStore(
    fileName = "${FAVORITE_ORDER_STORE.name}.json",
    serializer = JsonSerializer(FavoriteOrderArchive.serializer(), FavoriteOrderArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("FavoriteOrderStore", "收藏曲序数据损坏，已重置为空", throwable)
        FavoriteOrderArchive()
    },
)

/**
 * 收藏歌单曲序存储。
 *
 * 收藏歌单的成员由曲库收藏集即时解析（见 PlaylistGrouping.resolveSourceTracks），
 * 集合本身不携带次序——用户拖拽调整后记一份曲目 id 序列，展示与播放都按它重排。
 *
 * 只记次序不记成员：收藏增删后按 id 查表过滤，序列里已消失的 id 自然落空，
 * 新收藏的曲目排在其后，下次拖拽整条覆盖。DataStore 是唯一事实源，
 * 写入整份表一次提交，读取由 [order] 的收集侧持续回流。
 */
class FavoriteOrderStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    var order by mutableStateOf<List<Long>>(emptyList())
        private set

    // 首轮读盘完成：启动期重建播放来源前先等它，避免以空曲序覆盖用户已保存的顺序
    private val initialLoad = CompletableDeferred<Unit>()

    init {
        scope.launch {
            appContext.favoriteOrderDataStore.data
                .catchPersistenceIo("FavoriteOrderStore") { FavoriteOrderArchive() }
                .collect { archive ->
                    order = archive.order
                    initialLoad.complete(Unit)
                }
        }
    }

    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    suspend fun setOrder(orderedIds: List<Long>) {
        if (orderedIds.isEmpty()) return
        appContext.favoriteOrderDataStore.updateData { archive ->
            archive.copy(order = orderedIds.distinct())
        }
    }
}
