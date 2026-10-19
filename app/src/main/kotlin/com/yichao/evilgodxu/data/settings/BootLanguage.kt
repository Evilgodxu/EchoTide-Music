package com.yichao.evilgodxu.data.settings

import android.content.Context
import androidx.core.content.edit
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind

// 启动语言镜像：与 settings DataStore 并存的单键副本，DataStore 始终是唯一事实源。
// 存在的唯一理由是 attachBaseContext 须在主线程同步拿到启动语言，而 DataStore 首次读取要实例化并
// 反序列化整份配置，放在启动关键路径上就是一处主线程阻塞。写入点只有「语言变更」与「启动预热」两处。
// 名字即持久化标识，存储页统计也取这一处
internal val BOOT_LANGUAGE_STORE = PersistedData("app_language_boot", PersistedFileKind.SHARED_PREFS)
private const val BOOT_KEY = "language"

// 同步读取启动语言镜像；从未写入过（首次安装 / 从旧版本升级）时返回 null，由调用方回退读 DataStore
fun readBootLanguage(context: Context): AppLanguage? {
    val raw = runCatching {
        context.getSharedPreferences(BOOT_LANGUAGE_STORE.name, Context.MODE_PRIVATE).getString(BOOT_KEY, null)
    }.getOrNull() ?: return null
    return AppLanguage.entries.find { it.name == raw }
}

// 写入启动语言镜像。同步落盘：异步写盘在进程被杀时会让镜像滞后一次启动，
// 表现为语言偶发回退；调用点均已切至 IO 线程，不阻塞主线程
fun writeBootLanguage(context: Context, language: AppLanguage) {
    runCatching {
        context.getSharedPreferences(BOOT_LANGUAGE_STORE.name, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString(BOOT_KEY, language.name)
            }
    }
}
