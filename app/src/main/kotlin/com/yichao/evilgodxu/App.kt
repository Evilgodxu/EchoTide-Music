package com.yichao.evilgodxu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.StrictMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.playlist.ArtistOrderStore
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.data.settings.bootstrapAppLanguage
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.usbExclusiveOutputModeFlow
import com.yichao.evilgodxu.data.settings.writeBootLanguage
import com.yichao.evilgodxu.floatingwindow.LocalMiniPlayerController
import com.yichao.evilgodxu.floatingwindow.MiniPlayerController
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.service.setUsbAttachEntryEnabled
import com.yichao.evilgodxu.update.LocalUpdateViewModel
import com.yichao.evilgodxu.update.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

// 应用入口，同时作为进程级单例的中心持有者：各应用级依赖挂在 App 上
// 经 ProvideAppDependencies 注入 Compose 树，系统创建的组件经 (application as App) 取用
class App : Application() {

    /**
     * 进程级作用域。
     *
     * 供没有自身生命周期的系统组件使用：例如 USB 接入那个无界面入口——它在 onCreate 里就要结束，
     * 绑定到 Activity 生命周期的作用域会被随即取消，落盘根本来不及发生。
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val localizationManager: LocalizationManager by lazy { LocalizationManager(this) }
    val playlistStore: PlaylistStore by lazy { PlaylistStore() }
    val artistOrderStore: ArtistOrderStore by lazy { ArtistOrderStore() }
    val metadataEnricher: MetadataEnricher by lazy { MetadataEnricher() }
    val playlistRefresher: PlaylistRefresher by lazy { PlaylistRefresher(playlistStore) }
    val stateHolder: MusicPanelStateHolder by lazy {
        MusicPanelStateHolder(metadataEnricher, playlistStore)
    }
    // 迷你播放器控制器单例：应用级悬浮窗生命周期，全库共享同一实例
    val miniPlayerController: MiniPlayerController by lazy {
        MiniPlayerController(this, stateHolder, playlistRefresher, metadataEnricher)
    }
    // 更新检查以单例共享，主页自动检查与设置页手动检查读写同一状态
    val updateViewModel: UpdateViewModel by lazy {
        UpdateViewModel(this, settingsRepository, localizationManager)
    }
    val appVersion: String by lazy { readAppVersion() }

    override fun onCreate() {
        super.onCreate()
        // 调试构建启用线程策略：让主线程磁盘/网络访问在开发期直接暴露
        enableStrictModeInDebugBuild()
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
        // 首帧前同步预置上次播放状态（见 seedFromBootMirror），使首页首帧即是完整内容而非空态；
        // 这是启动关键路径上的刻意读盘，异步化会把空窗推回首帧之后
        stateHolder.state.seedFromBootMirror(applicationContext)
        // 冷启动尽早恢复持久化播放列表：恢复任务由首个调用方触发（见 restoreSavedState），
        // 此处预触发使首帧渲染时歌单与当前曲目已就绪，避免首页先以空态展示再等待首帧后的恢复
        appScope.launch {
            runCatching { stateHolder.state.restoreSavedState(applicationContext) }
        }
        // 预热设置 DataStore，并把启动语言写入轻量镜像：
        // 下次冷启动 attachBaseContext 即可同步命中镜像，无需等待 DataStore 首次读取
        appScope.launch {
            runCatching { settingsDataStore.data.first() }
            runCatching { writeBootLanguage(applicationContext, applicationContext.bootstrapAppLanguage()) }
        }
        // USB 接入入口跟着独占开关启停：开关关着时该入口不认领设备，系统也就不会弹「用哪个应用打开」。
        // 订阅落盘值而非开关回调，故设置页之外的写入同样生效（见 setUsbAttachEntryEnabled）
        appScope.launch {
            runCatching { usbExclusiveOutputModeFlow().collect { setUsbAttachEntryEnabled(it) } }
        }
        // 内存缓存收窄到进程堆的 10%，把堆留给 ExoPlayer 的高解析度音频缓冲；
        // 磁盘缓存关闭：其私有键值格式的占用不可见，且与 LargeCoverStore 的封面落盘重复，
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

    private fun readAppVersion(): String =
        packageManager
            .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            .versionName.orEmpty()

    // 用日志而非崩溃作为惩罚：生命周期内必要的 I/O 不该把调试包打断
    private fun enableStrictModeInDebugBuild() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
    }
}

// 应用级依赖的组合局部：Composable 只消费具体依赖，禁止直接引用 App。
// 宿主（Activity 与悬浮窗 Compose 宿主）统一经 ProvideAppDependencies 提供
val LocalMusicPanelStateHolder = staticCompositionLocalOf<MusicPanelStateHolder> {
    error("MusicPanelStateHolder is not provided")
}

val LocalPlaylistStore = staticCompositionLocalOf<PlaylistStore> {
    error("PlaylistStore is not provided")
}

val LocalArtistOrderStore = staticCompositionLocalOf<ArtistOrderStore> {
    error("ArtistOrderStore is not provided")
}

val LocalMetadataEnricher = staticCompositionLocalOf<MetadataEnricher> {
    error("MetadataEnricher is not provided")
}

val LocalPlaylistRefresher = staticCompositionLocalOf<PlaylistRefresher> {
    error("PlaylistRefresher is not provided")
}

val LocalApplication = staticCompositionLocalOf<Application> {
    error("Application is not provided")
}

val LocalSettingsRepository = staticCompositionLocalOf<SettingsRepository> {
    error("SettingsRepository is not provided")
}

val LocalLocalizationManager = staticCompositionLocalOf<LocalizationManager> {
    error("LocalizationManager is not provided")
}

// 统一注入应用级依赖：宿主在界面树根部调用一次，避免各处重复罗列组合局部
@Composable
fun ProvideAppDependencies(app: App, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMiniPlayerController provides app.miniPlayerController,
        LocalUpdateViewModel provides app.updateViewModel,
        LocalMusicPanelStateHolder provides app.stateHolder,
        LocalPlaylistStore provides app.playlistStore,
        LocalArtistOrderStore provides app.artistOrderStore,
        LocalMetadataEnricher provides app.metadataEnricher,
        LocalPlaylistRefresher provides app.playlistRefresher,
        LocalApplication provides app,
        LocalSettingsRepository provides app.settingsRepository,
        LocalLocalizationManager provides app.localizationManager,
        content = content,
    )
}