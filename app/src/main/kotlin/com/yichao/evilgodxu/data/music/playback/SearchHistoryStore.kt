package com.yichao.evilgodxu.data.music.playback

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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal val SEARCH_HISTORY_STORE = PersistedData("search_history", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class SearchHistoryArchive(
    val entries: List<String> = emptyList(),
)

private val Context.searchHistoryDataStore: DataStore<SearchHistoryArchive> by dataStore(
    fileName = "${SEARCH_HISTORY_STORE.name}.json",
    serializer = JsonSerializer(SearchHistoryArchive.serializer(), SearchHistoryArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("SearchHistoryStore", "搜索历史数据损坏，已重置为空", throwable)
        SearchHistoryArchive()
    },
)

/**
 * 搜索历史：最近 10 条，去重且最新的排在最前。
 *
 * DataStore 持有持久化状态；内存中的 [entries] 只服务 Compose 的同步读取，
 * 写入经单一写协程合流，连续输入只落最后一次。
 */
internal class SearchHistoryStore(context: Context, scope: CoroutineScope) {

    private companion object {
        const val MAX_ENTRIES = 10
    }

    private val appContext = context.applicationContext
    private val writeQueue = Channel<List<String>>(Channel.CONFLATED)
    private val loaded = CompletableDeferred<Unit>()

    private var items by mutableStateOf<List<String>>(emptyList())

    val entries: List<String> get() = items

    init {
        scope.launch {
            appContext.searchHistoryDataStore.data
                .catchPersistenceIo("SearchHistoryStore") { SearchHistoryArchive() }
                .collect { archive ->
                    items = archive.entries
                    loaded.complete(Unit)
                }
        }
        scope.launch {
            for (entries in writeQueue) {
                appContext.searchHistoryDataStore.updateData { SearchHistoryArchive(entries) }
            }
        }
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    fun add(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        items = (listOf(normalized) + items.filterNot { it == normalized }).take(MAX_ENTRIES)
        writeQueue.trySend(items)
    }

    fun remove(query: String) {
        items = items.filterNot { it == query }
        writeQueue.trySend(items)
    }

    fun clear() {
        items = emptyList()
        writeQueue.trySend(items)
    }
}
