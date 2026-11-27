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

// 歌手曲序存储落点，名字即持久化标识
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

/** 歌手曲序存储，DataStore 为准 */
class ArtistOrderStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    // 歌手名 → 曲目次序；只记次序，改名后的旧条目留作残留
    var orders by mutableStateOf<Map<String, List<Long>>>(emptyMap())
        private set

    // 等首轮读盘，避免空曲序覆盖已存顺序
    private val initialLoad = CompletableDeferred<Unit>()

    init {
        scope.launch {
            appContext.artistOrderDataStore.data
                .catchPersistenceIo("ArtistOrderStore") { ArtistOrderArchive() }
                .collect { archive ->
                    orders = archive.orders
                    initialLoad.complete(Unit)
                }
        }
    }

    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    suspend fun setOrder(artist: String, orderedIds: List<Long>) {
        if (artist.isBlank() || orderedIds.isEmpty()) return
        appContext.artistOrderDataStore.updateData { archive ->
            archive.copy(orders = archive.orders + (artist to orderedIds.distinct()))
        }
    }
}
