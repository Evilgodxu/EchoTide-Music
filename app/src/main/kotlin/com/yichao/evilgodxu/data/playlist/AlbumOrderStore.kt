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

// 专辑曲序的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
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
 * 专辑分组曲序存储。
 *
 * 专辑分组的曲目由曲库按 albumId 即时解析（见 PlaylistGrouping.albumGroups），顺序本身没有落脚点——
 * 用户拖拽调整后按分组 key（`album:<albumId>`）记一份曲目 id 序列，进页时按它重排。
 * 专辑改名会改绑 albumId（见 albumIdentity），分组 key 随之改变，旧条目自然落空：
 * 曲目已属另一张专辑，沿用旧次序不再有意义。
 *
 * DataStore 是唯一事实源，写入整份表一次提交，读取由 [orders] 的收集侧持续回流。
 */
class AlbumOrderStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    // 专辑分组 key → 该专辑曲目的自定义次序。只记次序不记成员：曲库增删后按 id 查表过滤，
    // 序列里已消失的 id 自然落空，下次拖拽整条覆盖时随之清掉
    var orders by mutableStateOf<Map<String, List<Long>>>(emptyMap())
        private set

    // 首轮读盘完成：启动期重建播放来源前先等它，避免以空曲序覆盖用户已保存的顺序
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
