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

// 专辑曲序存储落点，名字即持久化标识
internal val ALBUM_ORDER_STORE = PersistedData("album_orders", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class AlbumOrderArchive(
    val orders: Map<String, List<Long>> = emptyMap(),
)

private val Context.albumOrderDataStore: DataStore<AlbumOrderArchive> by dataStore(
    fileName = "${ALBUM_ORDER_STORE.name}.json",
    serializer = JsonSerializer(AlbumOrderArchive.serializer(), AlbumOrderArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("AlbumOrderStore", "专辑曲序数据损坏，已重置为空", throwable)
        AlbumOrderArchive()
    },
)

/**
 * 专辑曲序存储；按分组 key 记录次序，专辑改名后旧条目落空
 * DataStore 为唯一事实源，写入整份提交
 */
class AlbumOrderStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    // 分组 key → 曲目次序；只记次序，消失的 id 落空
    var orders by mutableStateOf<Map<String, List<Long>>>(emptyMap())
        private set

    // 等首轮读盘，避免空曲序覆盖已存顺序
    private val initialLoad = CompletableDeferred<Unit>()

    init {
        scope.launch {
            appContext.albumOrderDataStore.data
                .catchPersistenceIo("AlbumOrderStore") { AlbumOrderArchive() }
                .collect { archive ->
                    orders = archive.orders
                    initialLoad.complete(Unit)
                }
        }
    }

    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    suspend fun setOrder(albumKey: String, orderedIds: List<Long>) {
        if (albumKey.isBlank() || orderedIds.isEmpty()) return
        appContext.albumOrderDataStore.updateData { archive ->
            archive.copy(orders = archive.orders + (albumKey to orderedIds.distinct()))
        }
    }
}
