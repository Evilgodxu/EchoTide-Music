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
import com.yichao.evilgodxu.data.playlist.AlbumOrderStore
import com.yichao.evilgodxu.data.playlist.ArtistOrderStore
import com.yichao.evilgodxu.data.playlist.FavoriteOrderStore
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

/** 应用级依赖容器：只构造持有单例 */
class AppContainer(val application: Application) {

    /** 进程级作用域：随进程存活，不随 Activity 取消 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(application) }

    val localizationManager: LocalizationManager by lazy { LocalizationManager(application) }

    val playlistStore: PlaylistStore by lazy { PlaylistStore(application, appScope) }

    val artistOrderStore: ArtistOrderStore by lazy { ArtistOrderStore(application, appScope) }

    val albumOrderStore: AlbumOrderStore by lazy { AlbumOrderStore(application, appScope) }

    val favoriteOrderStore: FavoriteOrderStore by lazy { FavoriteOrderStore(application, appScope) }

    val proxySourceStore: ProxySourceStore by lazy { ProxySourceStore(application, appScope) }

    val metadataEnricher: MetadataEnricher by lazy { MetadataEnricher() }

    val playlistRefresher: PlaylistRefresher by lazy {
        PlaylistRefresher(playlistStore, albumOrderStore, artistOrderStore, favoriteOrderStore)
    }

    val playbackState: MusicPlaybackState by lazy {
        MusicPlaybackState(
            appContext = application,
            persistenceScope = appScope,
            metadataEnricher = metadataEnricher,
            playlistStore = playlistStore,
            proxySourceStore = proxySourceStore,
        )
    }

    // 迷你播放器单例：应用级悬浮窗生命周期
    val miniPlayerController: MiniPlayerController by lazy {
        MiniPlayerController(application, playbackState, playlistRefresher, metadataEnricher)
    }

    // 更新检查单例：主页与设置页共享状态
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

// 依赖由 ProvideAppDependencies 提供
val LocalPlaybackState = staticCompositionLocalOf<MusicPlaybackState> {
    error("MusicPlaybackState is not provided")
}

val LocalPlaylistStore = staticCompositionLocalOf<PlaylistStore> {
    error("PlaylistStore is not provided")
}

val LocalArtistOrderStore = staticCompositionLocalOf<ArtistOrderStore> {
    error("ArtistOrderStore is not provided")
}

val LocalAlbumOrderStore = staticCompositionLocalOf<AlbumOrderStore> {
    error("AlbumOrderStore is not provided")
}

val LocalFavoriteOrderStore = staticCompositionLocalOf<FavoriteOrderStore> {
    error("FavoriteOrderStore is not provided")
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

// 界面树根部统一注入应用级依赖
@Composable
fun ProvideAppDependencies(container: AppContainer, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMiniPlayerController provides container.miniPlayerController,
        LocalUpdateViewModel provides container.updateViewModel,
        LocalPlaybackState provides container.playbackState,
        LocalPlaylistStore provides container.playlistStore,
        LocalArtistOrderStore provides container.artistOrderStore,
        LocalAlbumOrderStore provides container.albumOrderStore,
        LocalFavoriteOrderStore provides container.favoriteOrderStore,
        LocalProxySourceStore provides container.proxySourceStore,
        LocalMetadataEnricher provides container.metadataEnricher,
        LocalPlaylistRefresher provides container.playlistRefresher,
        LocalApplication provides container.application,
        LocalSettingsRepository provides container.settingsRepository,
        LocalLocalizationManager provides container.localizationManager,
        content = content,
    )
}
