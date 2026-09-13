package com.yichao.evilgodxu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import coil3.disk.DiskCache
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.settings.bootstrapAppLanguage
import com.yichao.evilgodxu.data.settings.settingsDataStore
import com.yichao.evilgodxu.data.settings.writeBootLanguage
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 手动 DI 容器：Application 级单例，宿主 Activity/Service 启动时取用
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 调试构建启用线程策略：让主线程磁盘/网络访问在开发期直接暴露
        enableStrictModeInDebugBuild()
        // 最先初始化崩溃日志，捕获启动阶段异常
        CrashLogManager.init(this)
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

        container = AppContainer(this)
    }

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
