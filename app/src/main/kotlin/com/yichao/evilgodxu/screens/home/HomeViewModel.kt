package com.yichao.evilgodxu.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.MusicScanner
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.permission.PermissionMonitor
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    application: Application,
    private val playbackState: MusicPlaybackState,
    private val playlistRefresher: PlaylistRefresher,
    private val metadataEnricher: MetadataEnricher,
) : AndroidViewModel(application) {

    private val permissionMonitor = PermissionMonitor(getApplication())

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refreshPermissions()
    }

    // 只核对音频访问；弹窗回调与回前台都走这里
    fun refreshPermissions() {
        val wasGranted = _state.value.blockingPermissionsGranted
        _state.update { it.copy(mediaAudioGranted = permissionMonitor.isMediaAudioGranted()) }
        // 授权由无到有即首启动完成，开始建库
        if (!wasGranted && _state.value.blockingPermissionsGranted) {
            loadLibrary()
        }
    }

    // 曲库只加载一次：首启动建库，冷启动按签名补扫
    private var libraryLoadStarted = false

    // 每轮启动各项权限只问一次，拒绝后不再追问
    private val requestedStartupPermissions = mutableSetOf<String>()

    /** 登记申请；false 表示本轮已问过，应跳过 */
    fun markPermissionRequested(key: String): Boolean = requestedStartupPermissions.add(key)

    private fun loadLibrary() {
        if (libraryLoadStarted) return
        libraryLoadStarted = true
        viewModelScope.launch {
            val context = getApplication<Application>()
            val state = playbackState
            // 先恢复持久化歌单，避免扫描覆盖缓存
            state.restoreSavedState(context)
            if (needsLibraryScan(context, state)) {
                playlistRefresher.refresh(context, state, restoreCurrent = true)
                // 封面与歌词并行，先就绪先可见
                launch { state.artworkCache.warmUpArtwork(context) }
                metadataEnricher.enrichAndCleanup(context, state)
            }
            // 副歌扫描排在歌词后，避免争同一批文件；无变化时重复调用无代价
            state.highlightScan.ensureScan()
        }
    }

    /** 是否需要重扫：无缓存、签名变化或取不到签名都重扫 */
    private suspend fun needsLibraryScan(context: Application, state: MusicPlaybackState): Boolean {
        val saved = state.readLibrarySignature() ?: return true
        val current = MusicScanner.signature(context) ?: return true
        if (current != saved) return true
        // 签名相同但曲目更少即补扫；只判少，外部曲目不算
        val scannedCount = saved.substringBefore(':').toIntOrNull() ?: return true
        return state.libraryTracks.size < scannedCount
    }
}
