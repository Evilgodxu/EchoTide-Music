package com.yichao.evilgodxu

import android.app.Application
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.SettingsKeys
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.usbExclusiveOutputModeFlow
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.service.setUsbAttachEntryEnabled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 应用入口：建容器并编排启动
class App : Application() {

    val container: AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        val appScope = container.appScope
        val playbackState = container.playbackState
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
        // 首帧前同步预置播放状态；无镜像则跳过，异步会露空态
        if (playbackState.hasBootMirror(this)) {
            playbackState.seedFromBootMirror(this)
        }
        // 提前恢复播放列表，使首帧歌单与曲目就绪
        appScope.launch {
            runCatching { playbackState.restoreSavedState(applicationContext) }
        }
        // 预热设置并同步系统应用语言，冷启动首帧即正确
        appScope.launch {
            val preferences = runCatching { settingsDataStore.data.first() }.getOrNull()
            preferences?.let {
                container.localizationManager.applySystemLocale(
                    AppLanguage.fromTag(it[SettingsKeys.LANGUAGE]),
                )
            }
        }
        // USB 入口跟随独占开关；订阅落盘值，设置页外写入亦生效
        appScope.launch {
            runCatching {
                usbExclusiveOutputModeFlow().collect { setUsbAttachEntryEnabled(it) }
            }
        }
        // 冷启动清理上次遗留的中转文件
        appScope.launch {
            runCatching { CacheInventory.reclaimOnColdStart(this@App) }
        }
    }
}
