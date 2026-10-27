package com.yichao.evilgodxu.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.MusicScanner
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.permission.PermissionMonitor
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    application: Application,
    private val stateHolder: MusicPanelStateHolder,
    private val playlistRefresher: PlaylistRefresher,
    private val metadataEnricher: MetadataEnricher,
) : AndroidViewModel(application) {

    private val permissionMonitor = PermissionMonitor(getApplication())

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refreshPermissions()
    }

    // 只核对音频访问：其余权限由各自功能点在自己触发时申请，启动路径不再逐项探测系统状态
    fun refreshPermissions() {
        val wasGranted = _state.value.blockingPermissionsGranted
        _state.update { it.copy(mediaAudioGranted = permissionMonitor.isMediaAudioGranted()) }
        // 音频访问从未满足变为满足，即首启动的授权环节完成，进入建库
        if (!wasGranted && _state.value.blockingPermissionsGranted) {
            loadLibrary()
        }
    }

    // 曲库加载只做一次：首启动扫描建库，冷启动仅在曲库签名变化时补扫
    private var libraryLoadStarted = false

    // 启动期各项权限每轮最多申请一次：拒绝后不再追问，等下次冷启动（ViewModel 重建）重走一条链
    private val requestedStartupPermissions = mutableSetOf<String>()

    /** 登记一次申请；返回 false 表示本轮已经问过，调用方应跳过该项继续推进。 */
    fun markPermissionRequested(key: String): Boolean = requestedStartupPermissions.add(key)

    private fun loadLibrary() {
        if (libraryLoadStarted) return
        libraryLoadStarted = true
        viewModelScope.launch {
            val context = getApplication<Application>()
            val state = stateHolder.state
            // 先恢复持久化歌单，避免扫描覆盖已缓存的封面/歌词
            state.restoreSavedState(context)
            if (needsLibraryScan(context, state)) {
                playlistRefresher.refresh(context, state, restoreCurrent = true)
                // 封面与歌词互不依赖：两路并行，谁先就绪谁先可见
                launch { state.warmUpArtwork(context) }
                metadataEnricher.enrichAndCleanup(context, state)
            }
            // 副歌片段表排在歌词之后：扫描只读歌词文件，此时缓存已就位，两者不再并发争同一批文件。
            // 只处理未扫过或歌词已变的曲目，无变化时重复调用无代价
            state.ensureHighlightScan(context)
        }
    }

    /**
     * 是否需要重新扫描曲库。
     *
     * 首启动（无缓存）必然要扫；冷启动只在签名变化时扫。签名取不到（老版本升级、缓存被清）
     * 说明无从比对，同样按需要扫处理，宁可多扫一轮也不漏掉新入库的歌。
     */
    private suspend fun needsLibraryScan(context: Application, state: MusicPlaybackState): Boolean {
        if (!state.hasLibraryCache(context)) return true
        val saved = state.readLibrarySignature(context) ?: return true
        val current = MusicScanner.signature(context) ?: return true
        if (current != saved) return true
        // 签名相同但缓存里的曲目比它少：上一轮的歌单落盘被进程结束打断，按需要补扫。
        // 只判「少」——缓存里还可能多出外部与在线曲目，那不是需要重扫的理由
        val scannedCount = saved.substringBefore(':').toIntOrNull() ?: return true
        return state.libraryTracks.size < scannedCount
    }
}
