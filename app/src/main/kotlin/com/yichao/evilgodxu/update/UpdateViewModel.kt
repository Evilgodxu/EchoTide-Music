package com.yichao.evilgodxu.update

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.localization.LocalizationManager
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// 更新检查与下载状态的统一管理，供主页与设置页共用，
// 以单例形式注册，保证两处读写同一状态，对话框由 Activity 全局弹出
class UpdateViewModel(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val localizationManager: LocalizationManager,
) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>().applicationContext

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    // 手动检查结果的一次性提示：无回放，自动检查静默、仅手动检查反馈
    private val _messages = MutableSharedFlow<CheckFeedback>(extraBufferCapacity = 1)
    val messages: Flow<CheckFeedback> = _messages.asSharedFlow()

    enum class CheckFeedback { UP_TO_DATE, ERROR }

    fun checkForUpdate(force: Boolean = false) {
        viewModelScope.launch {
            var checkFailed = false
            val result = UpdateManager.checkForUpdate(
                context,
                force = force,
                onError = { checkFailed = true }
            )
            if (result != null) {
                _updateInfo.value = result
                _showUpdateDialog.value = true
                return@launch
            }
            // 自动检查静默：仅手动检查反馈一次性提示
            if (!force) return@launch
            _messages.emit(if (checkFailed) CheckFeedback.ERROR else CheckFeedback.UP_TO_DATE)
        }
    }

    fun downloadAndInstall() {
        val info = _updateInfo.value ?: return
        _downloadState.value = DownloadState.Downloading(0f)
        viewModelScope.launch {
            // 下载通知文案面向用户：按当前应用语言构造本地化 Context 取资源，不使用系统 Context
            val language = settingsRepository.getAppLanguage()
            val localizedContext = localizationManager.createLocalizedContext(
                localizationManager.resolveLanguage(language),
            )
            val success = UpdateManager.downloadAndInstall(localizedContext, info) { progress ->
                _downloadState.value = if (progress < 0f) {
                    DownloadState.Failed("download_failed")
                } else {
                    DownloadState.Downloading(progress)
                }
            }
            if (success) {
                _downloadState.value = DownloadState.Success
                _showUpdateDialog.value = false
            } else if (_downloadState.value !is DownloadState.Failed) {
                _downloadState.value = DownloadState.Failed("download_failed")
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
        _downloadState.value = DownloadState.Idle
        viewModelScope.launch { UpdateManager.clearPendingUpdate(context) }
    }
}

// 供界面树消费的组合局部，由宿主 Activity 提供
val LocalUpdateViewModel = staticCompositionLocalOf<UpdateViewModel> {
    error("UpdateViewModel is not provided")
}
