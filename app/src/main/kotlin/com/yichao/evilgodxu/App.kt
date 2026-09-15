package com.yichao.evilgodxu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.StrictMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.disk.DiskCache
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.data.settings.bootstrapAppLanguage
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.writeBootLanguage
import com.yichao.evilgodxu.floatingwindow.LocalMusicPanelController
import com.yichao.evilgodxu.floatingwindow.MusicPanelController
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.update.LocalUpdateViewModel
import com.yichao.evilgodxu.update.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath

// 应用入口，同时作为进程级单例的中心持有者：各应用级依赖挂在 App 上
// 经 ProvideAppDependencies 注入 Compose 树，系统创建的组件经 (application as App) 取用
class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val localizationManager: LocalizationManager by lazy { LocalizationManager(this) }
    val playlistStore: PlaylistStore by lazy { PlaylistStore() }
    val metadataEnricher: MetadataEnricher by lazy { MetadataEnricher() }
    val playlistRefresher: PlaylistRefresher by lazy { PlaylistRefresher(playlistStore) }
    val stateHolder: MusicPanelStateHolder by lazy {
        MusicPanelStateHolder(metadataEnricher, playlistStore)
    }
    // 音乐面板/迷你播放器控制器单例：应用级悬浮窗生命周期，全库共享同一实例
    val musicPanelController: MusicPanelController by lazy {
        MusicPanelController(this, stateHolder, playlistRefresher, metadataEnricher)
    }
    // 更新检查以单例共享，主页自动检查与设置页手动检查读写同一状态
    val updateViewModel: UpdateViewModel by lazy {
        UpdateViewModel(this, settingsRepository, localizationManager)
    }
    // 应用版本号：冷启动读取一次
    val appVersion: String by lazy { readAppVersion() }

    override fun onCreate() {
        super.onCreate()
        // 调试构建启用线程策略：让主线程磁盘/网络访问在开发期直接暴露
        enableStrictModeInDebugBuild()
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
        // 首帧前同步预置上次播放状态：曲目、进度、背景取色与封面/歌词一并就位，
        // 使首页首帧即是完整内容，不必先渲染空态与占位符再等异步恢复（见 seedFromBootMirror）。
        // 这是启动关键路径上的刻意读盘，与语言镜像同理，异步化会让空窗又回到首帧上
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
        // 收窄图片内存缓存到进程堆的 10%，把堆留给 ExoPlayer 高解析度音频缓冲，
        // 缓解封面解码与播放并发时的 OOM；
        // 磁盘缓存显式限定上限：默认值按可用空间推算，会让封面占用随设备剩余空间无界增长
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.10)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve(CacheInventory.IMAGE_CACHE_DIR_NAME).toOkioPath())
                        .maxSizeBytes(CacheInventory.IMAGE_DISK_CACHE_MAX_BYTES)
                        .build()
                }
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

    // 仅调试构建启用：检测主线程磁盘读写与网络访问并输出日志。
    // 用日志而非崩溃作为惩罚，避免生命周期内必要的 I/O 直接把调试包打断
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
        LocalMusicPanelController provides app.musicPanelController,
        LocalUpdateViewModel provides app.updateViewModel,
        LocalMusicPanelStateHolder provides app.stateHolder,
        LocalPlaylistStore provides app.playlistStore,
        LocalMetadataEnricher provides app.metadataEnricher,
        LocalPlaylistRefresher provides app.playlistRefresher,
        LocalApplication provides app,
        LocalSettingsRepository provides app.settingsRepository,
        LocalLocalizationManager provides app.localizationManager,
        content = content,
    )
}