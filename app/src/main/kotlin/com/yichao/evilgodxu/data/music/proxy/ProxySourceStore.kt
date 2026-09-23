package com.yichao.evilgodxu.data.music.proxy

import android.content.Context
import android.content.SharedPreferences
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import org.json.JSONArray

// 代理音源持久化：SharedPreferences 存储原始 JSON 列表与启用状态，同名音源重复导入时覆盖
internal object ProxySourceStore {

    private const val PREFS_NAME = "proxy_sources"
    private const val KEY_SOURCES = "sources_json"
    private const val KEY_ENABLED = "enabled_names"

    // 写路径统一串行化，避免并发导入/移除/启停的读-改-写交错丢失更新
    @Synchronized
    fun import(context: Context, raw: String): ProxyParseResult {
        val parsed = ProxySourceParser.parse(raw)
        if (parsed is ProxyParseResult.Failure) return parsed
        val spec = (parsed as ProxyParseResult.Success).spec
        val list = rawList(context).toMutableList()
        list.removeAll { rawJson ->
            val existing = ProxySourceParser.parse(rawJson)
            existing is ProxyParseResult.Success && existing.spec.name == spec.name
        }
        list.add(spec.rawJson)
        // 新导入音源默认启用
        val enabled = enabledNames(context).toMutableSet()
        enabled.add(spec.name)
        // 音源列表与启用状态同属一份 XML，合并为一次落盘：拆成两次会多一倍 I/O，
        // 且中途进程被杀会留下「音源已导入但未启用」的不一致状态
        prefs(context).edit()
            .putString(KEY_SOURCES, encodeStringList(list))
            .putString(KEY_ENABLED, encodeStringList(enabled))
            .commit()
        return parsed
    }

    @Synchronized
    fun remove(context: Context, name: String) {
        val list = rawList(context).toMutableList()
        list.removeAll { rawJson ->
            val existing = ProxySourceParser.parse(rawJson)
            existing is ProxyParseResult.Success && existing.spec.name == name
        }
        val enabled = enabledNames(context).toMutableSet()
        enabled.remove(name)
        prefs(context).edit()
            .putString(KEY_SOURCES, encodeStringList(list))
            .putString(KEY_ENABLED, encodeStringList(enabled))
            .commit()
    }

    // 切换音源启用状态，停用的音源即时停止参与解析
    @Synchronized
    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        val names = enabledNames(context).toMutableSet()
        if (enabled) names.add(name) else names.remove(name)
        prefs(context).edit()
            .putString(KEY_ENABLED, encodeStringList(names))
            .commit()
    }

    // 全部已导入音源，按导入顺序返回（附带启用状态）；与写互斥保证列表与启用状态读取一致
    @Synchronized
    fun all(context: Context): List<ProxySourceSpec> {
        val enabled = enabledNames(context)
        return parsedSpecs(context).map { it.copy(enabled = enabled.contains(it.name)) }
    }

    // 自定义搜索平台：平台键 -> 平台名称，供平台切换菜单列举内置平台之外的候选。
    // 自定义平台没有内置搜索，缺搜索动作的平台选中后必然无结果，故只取声明了搜索动作的音源
    @Synchronized
    fun customSearchPlatforms(context: Context): Map<String, String> {
        val names = mutableMapOf<String, String>()
        for (spec in all(context)) {
            if (!spec.enabled) continue
            for ((key, platform) in spec.platforms) {
                if (key in MusicSearchSource.builtInKeys || platform.search == null) continue
                platform.name?.let { names[key] = it }
            }
        }
        return names
    }

    // 指定平台的生效音源：同一平台多音源时各动作独立取最近配置该动作的音源，
    // 不同音源可分别覆盖搜索/播放/歌词/封面/歌单等动作而不互相顶替
    fun platformSpec(context: Context, platform: String): ProxyPlatformSpec? {
        val covering = all(context)
            .filter { it.enabled && it.platforms.containsKey(platform) }
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
            playlist = latest { it.playlist },
        ).takeIf { it.hasAnyAction }
    }

    // 注册存储变更监听：分享导入等外部写入路径不经过 ViewModel，界面借此感知数据变化后刷新
    fun registerChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    // 字符串集合序列化为 JSON 数组文本，交由调用方与其它键合并到同一次落盘
    private fun encodeStringList(values: Collection<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    // 解析结果按原始 JSON 文本缓存：平台候选与展示名读取频繁，逐次重新解析全部音源文本代价过高；
    // 导入 / 移除会替换原文，缓存键不命中即重新解析
    private var cachedRawSources: String? = null
    private var cachedSpecs: List<ProxySourceSpec> = emptyList()

    private fun parsedSpecs(context: Context): List<ProxySourceSpec> {
        val raw = prefs(context).getString(KEY_SOURCES, null).orEmpty()
        if (raw == cachedRawSources) return cachedSpecs
        val specs = decodeRawList(raw).mapNotNull { rawJson ->
            (ProxySourceParser.parse(rawJson) as? ProxyParseResult.Success)?.spec
        }
        cachedRawSources = raw
        cachedSpecs = specs
        return specs
    }

    private fun rawList(context: Context): List<String> =
        decodeRawList(prefs(context).getString(KEY_SOURCES, null).orEmpty())

    // 音源文本列表本身也是 JSON 数组文本，整体损坏时按无音源处理
    private fun decodeRawList(raw: String): List<String> = try {
        val array = JSONArray(raw)
        List(array.length()) { array.optString(it).trim() }.filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun enabledNames(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_ENABLED, null) ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.optString(it).trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
