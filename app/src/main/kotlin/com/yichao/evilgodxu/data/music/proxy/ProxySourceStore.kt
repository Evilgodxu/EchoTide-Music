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

// 代理音源的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
internal val PROXY_SOURCE_STORE = PersistedData("proxy_sources", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class ProxySourceArchive(
    // 原始音源 JSON 文本：解析结果按原文缓存，导入/移除只替换原文
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

/**
 * 代理音源存储：DataStore 持有原始文本与启用状态，内存侧只缓存解析结果。
 *
 * 音源解析结果被搜索、平台菜单与设置页反复读取，逐次重新解析全部文本代价过高；
 * [sources] 在原文变化时才重新解析，启用状态变化只重映射标记。
 */
class ProxySourceStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val _sources = MutableStateFlow<List<ProxySourceSpec>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()

    /** 当前音源快照：设置页与平台菜单据此刷新，停用的音源也包含在内。 */
    val sources: StateFlow<List<ProxySourceSpec>> = _sources.asStateFlow()

    // 解析结果按原始 JSON 文本缓存：导入 / 移除会替换原文，缓存键不命中即重新解析
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

    /** 等待首次读盘完成：搜索与播放路径在取音源前调用，避免拿到尚未开始的空快照。 */
    suspend fun awaitLoaded() {
        loaded.await()
    }

    suspend fun import(raw: String): ProxyParseResult {
        val parsed = ProxySourceParser.parse(raw)
        if (parsed is ProxyParseResult.Failure) return parsed
        val spec = (parsed as ProxyParseResult.Success).spec
        // 音源列表与启用状态同属一份文件，合并为一次提交：拆成两次会留下
        // 「音源已导入但未启用」的中间态
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

    /** 全部音源（含停用项），按导入顺序。 */
    suspend fun all(): List<ProxySourceSpec> {
        awaitLoaded()
        return _sources.value
    }

    // 指定平台的生效音源：同一平台多音源时各动作独立取最近配置该动作的音源，
    // 不同音源可分别覆盖搜索/播放/歌词/封面等动作而不互相顶替
    suspend fun platformSpec(platform: String): ProxyPlatformSpec? {
        val covering = all().filter { it.enabled && it.platforms.containsKey(platform) }
        if (covering.isEmpty()) return null
        fun latest(selector: (ProxyPlatformSpec) -> ProxyActionSpec?): ProxyActionSpec? {
            // 正序遍历保留最近一个非空动作：后导入且配置了该动作的音源生效
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

    // 音源文本列表本身也是 JSON 数组文本，整体损坏时按无音源处理
    private fun decodeRawList(values: List<String>): List<String> =
        values.map(String::trim).filter(String::isNotEmpty)
}

// 自定义搜索平台：平台键 -> 平台名称。纯函数，供内存快照与平台菜单共用同一份判定
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
