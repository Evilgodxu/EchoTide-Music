package com.yichao.evilgodxu

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.settings.bootstrapAppLanguage
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.usbDirectOutputModeFlow
import com.yichao.evilgodxu.data.settings.usbExclusiveOutputModeFlow
import com.yichao.evilgodxu.data.settings.writeBootLanguage
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.service.setUsbAttachEntryEnabled
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 应用入口：构造依赖容器并编排启动顺序。
// 依赖本身由 AppContainer 持有，经 ProvideAppDependencies 注入 Compose 树；
// 系统创建的组件经 (application as App).container 取用
class App : Application() {

    val container: AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        val appScope = container.appScope
        val playbackState = container.playbackState
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
        // 上下文先于内容绑定：首启动没有任何可预置的内容，但后续落盘路径仍要取用上下文
        playbackState.attachContext(this)
        // 首帧前同步预置上次播放状态（见 seedFromBootMirror），使首页首帧即是完整内容而非空态；
        // 这是启动关键路径上的刻意读盘，异步化会把空窗推回首帧之后。
        // 首启动没有镜像可读，跳过这段同步读盘——首帧本就是空态，没有内容可预置
        if (playbackState.hasBootMirror(this)) {
            playbackState.seedFromBootMirror(this)
        }
        // 冷启动尽早恢复持久化播放列表：恢复任务由首个调用方触发（见 restoreSavedState），
        // 此处预触发使首帧渲染时歌单与当前曲目已就绪。
        // 首启动没有歌单缓存，预触发只会空跑一轮读盘，故跳过
        if (playbackState.hasLibraryCache(this)) {
            appScope.launch {
                runCatching { playbackState.restoreSavedState(applicationContext) }
            }
        }
        // 预热设置 DataStore，并把启动语言写入轻量镜像：
        // 下次冷启动 attachBaseContext 即可同步命中镜像，无需等待 DataStore 首次读取
        appScope.launch {
            runCatching { settingsDataStore.data.first() }
            runCatching { writeBootLanguage(applicationContext, applicationContext.bootstrapAppLanguage()) }
        }
        // USB 接入入口跟着两个 USB 开关的任一开启而启用：独占要用它换接管授权，直出要用那份授权读描述符
        // （面板的设备能力只认描述符，见 UsbAudioCapabilities）。两者都关着时该入口不认领设备，
        // 系统也就不会弹「用哪个应用打开」。订阅落盘值而非开关回调，故设置页之外的写入同样生效
        appScope.launch {
            runCatching {
                combine(usbExclusiveOutputModeFlow(), usbDirectOutputModeFlow()) { exclusive, direct ->
                    exclusive || direct
                }.collect { setUsbAttachEntryEnabled(it) }
            }
        }
        // 内存缓存收窄到进程堆的 10%，把堆留给 ExoPlayer 的高解析度音频缓冲；磁盘缓存关闭：
        // 其私有键值格式的占用不可见，且与 LargeCoverStore 的封面落盘重复，
        // 需要跨进程留存的只有当前/上一/下一三张大图
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                // 关掉硬件位图：主题切换的整屏快照以软件画布重绘，硬件位图进不了软件画布会打断快照；
                // 封面同步从系统缩略图侧收口到软件位图（见 MusicCoverLoader）
                .allowHardware(false)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.10)
                        .build()
                }
                .diskCache(null)
                .build()
        }

        // 冷启动回收上一次进程遗留的中转文件：启动时刻必然不存在本次下载，可整批清理
        appScope.launch {
            runCatching { CacheInventory.reclaimOnColdStart(this@App) }
        }
    }
}
