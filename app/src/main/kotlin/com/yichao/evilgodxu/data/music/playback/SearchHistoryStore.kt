package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 搜索历史：最近 10 条，去重且最新的排在最前；随每次增删同步落盘。
 */
internal class SearchHistoryStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private companion object {
        const val KEY = "music_search_history"
        const val MAX_ENTRIES = 10
    }

    private var items by mutableStateOf<List<String>>(emptyList())

    val entries: List<String> get() = items

    fun add(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        items = (listOf(normalized) + items.filterNot { it == normalized }).take(MAX_ENTRIES)
        persist()
    }

    fun remove(query: String) {
        items = items.filterNot { it == query }
        persist()
    }

    fun clear() {
        items = emptyList()
        persist()
    }

    fun replaceAll(value: List<String>) {
        items = value
    }

    /** 冷启动读盘：按行分隔，空白行忽略。 */
    fun read(context: Context): List<String> =
        context.getSharedPreferences(MusicPlaybackState.SEARCH_HISTORY_STORE.name, Context.MODE_PRIVATE)
            .getString(KEY, "")
            ?.split("\n")
            ?.filter(String::isNotBlank)
            .orEmpty()

    private fun persist() {
        val context = context() ?: return
        scope().launch(Dispatchers.IO) {
            context.getSharedPreferences(MusicPlaybackState.SEARCH_HISTORY_STORE.name, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    putString(KEY, items.joinToString("\n"))
                }
        }
    }
}
