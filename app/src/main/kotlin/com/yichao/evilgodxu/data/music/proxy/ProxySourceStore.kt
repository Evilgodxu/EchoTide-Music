package com.yichao.evilgodxu.data.music.proxy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.persistence.JsonSerializer
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.catchPersistenceIo
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 音源落盘点，读写与存储页统计共用此名
internal val PROXY_SOURCE_STORE = PersistedData("proxy_sources", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class ProxySourceArchive(
    // 原始音源 JSON，导入/移除只替换原文
    val sources: List<String> = emptyList(),
    val enabled: List<String> = emptyList(),
)

private val Context.proxySourceDataStore: DataStore<ProxySourceArchive> by dataStore(
    fileName = "${PROXY_SOURCE_STORE.name}.json",
    serializer = JsonSerializer(ProxySourceArchive.serializer(), ProxySourceArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("ProxySourceStore", "代理音源数据损坏，已重置为空", throwable)
        ProxySourceArchive()
    },
)

// DataStore 存原文与启用状态，内存缓存解析结果
// 原文变化才重新解析，启用状态变化只重映射标记
class ProxySourceStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val _sources = MutableStateFlow<List<ProxySourceSpec>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()

    // 当前音源快照，含停用项，供设置页与菜单刷新
    val sources: StateFlow<List<ProxySourceSpec>> = _sources.asStateFlow()

    // 解析结果按原文缓存，键不命中才重新解析
    private var cachedRawKey: String? = null
    private var cachedSpecs: List<ProxySourceSpec> = emptyList()

    init {
        scope.launch {
            appContext.proxySourceDataStore.data
                .catchPersistenceIo("ProxySourceStore") { ProxySourceArchive() }
                .collect { archive ->
                    _sources.value = parseArchive(archive)
                    loaded.complete(Unit)
                }
        }
    }

    // 等待首次读盘完成，避免拿到空快照
    suspend fun awaitLoaded() {
        loaded.await()
    }

    suspend fun import(raw: String): ProxyParseResult {
        val parsed = ProxySourceParser.parse(raw)
        if (parsed is ProxyParseResult.Failure) return parsed
        val spec = (parsed as ProxyParseResult.Success).spec
        // 列表与启用状态合并为一次提交，避免中间态
        appContext.proxySourceDataStore.updateData { archive ->
            val sources = archive.sources.filterNot { rawJson ->
                val existing = ProxySourceParser.parse(rawJson)
                existing is ProxyParseResult.Success && existing.spec.name == spec.name
            } + spec.rawJson
            archive.copy(
                sources = sources,
                enabled = (archive.enabled + spec.name).distinct(),
            )
        }
        return parsed
    }

    suspend fun remove(name: String) {
        appContext.proxySourceDataStore.updateData { archive ->
            archive.copy(
                sources = archive.sources.filterNot { rawJson ->
                    val existing = ProxySourceParser.parse(rawJson)
                    existing is ProxyParseResult.Success && existing.spec.name == name
                },
                enabled = archive.enabled.filterNot { it == name },
            )
        }
    }

    // 停用的音源即时停止参与解析
    suspend fun setEnabled(name: String, enabled: Boolean) {
        appContext.proxySourceDataStore.updateData { archive ->
            archive.copy(
                enabled = if (enabled) {
                    (archive.enabled + name).distinct()
                } else {
                    archive.enabled.filterNot { it == name }
                },
            )
        }
    }

    // 全部音源（含停用项），按导入顺序
    suspend fun all(): List<ProxySourceSpec> {
        awaitLoaded()
        return _sources.value
    }

    // 生效音源：各动作独立取最近配置该动作的音源
    // 不同音源可分别覆盖搜索/播放/歌词/封面
    suspend fun platformSpec(platform: String): ProxyPlatformSpec? {
        val covering = all().filter { it.enabled && it.platforms.containsKey(platform) }
        if (covering.isEmpty()) return null
        fun latest(selector: (ProxyPlatformSpec) -> ProxyActionSpec?): ProxyActionSpec? {
            // 正序遍历保留最近非空动作，后导入者生效
            var found: ProxyActionSpec? = null
            covering.forEach { spec ->
                selector(spec.platforms.getValue(platform))?.let { found = it }
            }
            return found
        }
        return ProxyPlatformSpec(
            search = latest { it.search },
            url = latest { it.url },
            lyric = latest { it.lyric },
            pic = latest { it.pic },
        ).takeIf { it.hasAnyAction }
    }

    private fun parseArchive(archive: ProxySourceArchive): List<ProxySourceSpec> {
        val rawKey = archive.sources.joinToString("\u0000")
        val specs = if (rawKey == cachedRawKey) {
            cachedSpecs
        } else {
            decodeRawList(archive.sources).mapNotNull { rawJson ->
                (ProxySourceParser.parse(rawJson) as? ProxyParseResult.Success)?.spec
            }.also {
                cachedRawKey = rawKey
                cachedSpecs = it
            }
        }
        val enabled = archive.enabled.toSet()
        return specs.map { it.copy(enabled = enabled.contains(it.name)) }
    }

    // 列表整体损坏时按无音源处理
    private fun decodeRawList(values: List<String>): List<String> =
        values.map(String::trim).filter(String::isNotEmpty)
}

// 自定义搜索平台映射，供快照与菜单共用
internal fun customSearchPlatforms(sources: List<ProxySourceSpec>): Map<String, String> {
    val names = mutableMapOf<String, String>()
    for (spec in sources) {
        if (!spec.enabled) continue
        for ((key, platform) in spec.platforms) {
            if (key in MusicSearchSource.builtInKeys || platform.search == null) continue
            platform.name?.let { names[key] = it }
        }
    }
    return names
}
