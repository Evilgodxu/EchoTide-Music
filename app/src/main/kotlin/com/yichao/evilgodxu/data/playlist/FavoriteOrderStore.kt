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

// 收藏曲序存储落点，名字即持久化标识
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
 * 收藏曲序存储；只记次序不记成员，成员由收藏集即时解析
 * DataStore 为唯一事实源，写入整份提交
 */
class FavoriteOrderStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    var order by mutableStateOf<List<Long>>(emptyList())
        private set

    // 等首轮读盘，避免空曲序覆盖已存顺序
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
