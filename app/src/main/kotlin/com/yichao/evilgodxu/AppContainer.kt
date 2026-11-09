package com.yichao.evilgodxu

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore
import com.yichao.evilgodxu.data.playlist.ArtistOrderStore
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.repository.DataStoreSettingsRepository
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.floatingwindow.LocalMiniPlayerController
import com.yichao.evilgodxu.floatingwindow.MiniPlayerController
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.update.LocalUpdateViewModel
import com.yichao.evilgodxu.update.UpdateStore
import com.yichao.evilgodxu.update.UpdateViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级依赖容器：一站式持有进程内单例，由 `App` 构造并持有。
 *
 * 只负责构造与持有依赖，不做启动编排——启动顺序属于 `App.onCreate`；
 * 同理也不对外转发成员，取用方直接经 `App.container` 访问，避免多一层只做转发的中间层。
 */
class AppContainer(val application: Application) {

    /**
     * 进程级作用域。
     *
     * 供没有自身生命周期的系统组件使用：例如 USB 接入那个无界面入口——它在 onCreate 里就要结束，
     * 绑定到 Activity 生命周期的作用域会被随即取消，落盘根本来不及发生。
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(application) }

    val localizationManager: LocalizationManager by lazy { LocalizationManager(application) }

    val playlistStore: PlaylistStore by lazy { PlaylistStore(application, appScope) }

    val artistOrderStore: ArtistOrderStore by lazy { ArtistOrderStore(application, appScope) }

    val proxySourceStore: ProxySourceStore by lazy { ProxySourceStore(application, appScope) }

    val metadataEnricher: MetadataEnricher by lazy { MetadataEnricher() }

    val playlistRefresher: PlaylistRefresher by lazy { PlaylistRefresher(playlistStore) }

    val playbackState: MusicPlaybackState by lazy {
        MusicPlaybackState(
            appContext = application,
            persistenceScope = appScope,
            metadataEnricher = metadataEnricher,
            playlistStore = playlistStore,
            proxySourceStore = proxySourceStore,
        )
    }

    // 迷你播放器控制器单例：应用级悬浮窗生命周期，全库共享同一实例
    val miniPlayerController: MiniPlayerController by lazy {
        MiniPlayerController(application, playbackState, playlistRefresher, metadataEnricher)
    }

    // 更新检查以单例共享，主页自动检查与设置页手动检查读写同一状态
    val updateStore: UpdateStore by lazy { UpdateStore(application, appScope) }

    val updateViewModel: UpdateViewModel by lazy {
        UpdateViewModel(application, settingsRepository, localizationManager, updateStore)
    }

    // 应用版本号：冷启动读取一次
    val appVersion: String by lazy {
        application.packageManager
            .getPackageInfo(application.packageName, PackageManager.PackageInfoFlags.of(0L))
            .versionName.orEmpty()
    }
}

// 应用级依赖的组合局部：Composable 只消费具体依赖，禁止直接引用 App。
// 宿主（Activity 与悬浮窗 Compose 宿主）统一经 ProvideAppDependencies 提供
val LocalPlaybackState = staticCompositionLocalOf<MusicPlaybackState> {
    error("MusicPlaybackState is not provided")
}

val LocalPlaylistStore = staticCompositionLocalOf<PlaylistStore> {
    error("PlaylistStore is not provided")
}

val LocalArtistOrderStore = staticCompositionLocalOf<ArtistOrderStore> {
    error("ArtistOrderStore is not provided")
}

val LocalProxySourceStore = staticCompositionLocalOf<ProxySourceStore> {
    error("ProxySourceStore is not provided")
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
fun ProvideAppDependencies(container: AppContainer, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMiniPlayerController provides container.miniPlayerController,
        LocalUpdateViewModel provides container.updateViewModel,
        LocalPlaybackState provides container.playbackState,
        LocalPlaylistStore provides container.playlistStore,
        LocalArtistOrderStore provides container.artistOrderStore,
        LocalProxySourceStore provides container.proxySourceStore,
        LocalMetadataEnricher provides container.metadataEnricher,
        LocalPlaylistRefresher provides container.playlistRefresher,
        LocalApplication provides container.application,
        LocalSettingsRepository provides container.settingsRepository,
        LocalLocalizationManager provides container.localizationManager,
        content = content,
    )
}
