package com.yichao.evilgodxu.screens.settings

import com.yichao.evilgodxu.data.music.proxy.ProxySourceSpec
import com.yichao.evilgodxu.data.repository.FakeSettingsRepository
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 设置页状态持有者：验证仓库回流、USB 互斥的乐观更新与三条授权流程的置位与回滚
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        repository: FakeSettingsRepository = FakeSettingsRepository(),
        environment: FakeSettingsEnvironment = FakeSettingsEnvironment(),
    ) = SettingsViewModel(repository, environment)

    @Test
    fun `版本与代理列表在初始化时取自环境`() = runTest(dispatcher) {
        val viewModel = viewModel(environment = FakeSettingsEnvironment(appVersion = "6.2.4"))

        advanceUntilIdle()

        assertEquals("6.2.4", viewModel.uiState.value.version)
        assertTrue(viewModel.uiState.value.proxySources.isEmpty())
    }

    @Test
    fun `写入主题经仓库回流更新状态`() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val viewModel = viewModel(repository)

        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, viewModel.uiState.value.themeMode)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `播放设置写入经仓库回流更新状态`() = runTest(dispatcher) {
        val viewModel = viewModel()

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.playback.wordByWordRendering)

        viewModel.setWordByWordRendering(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.playback.wordByWordRendering)
    }

    @Test
    fun `开启 USB 直出会关闭独占并申请两项授权`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment(notificationPolicyGranted = false)
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        viewModel.setUsbExclusiveOutput(true)
        advanceUntilIdle()
        viewModel.setUsbDirectOutput(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.playback.usbDirectOutput)
        assertFalse(viewModel.uiState.value.playback.usbExclusiveOutput)
        assertTrue(environment.usbAccessRequested)
        assertTrue(environment.notificationPolicyWatched)
        assertTrue(viewModel.uiState.value.notificationPolicyPermissionPending)
    }

    @Test
    fun `免打扰已授予时不再申请`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment(notificationPolicyGranted = true)
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        viewModel.setUsbDirectOutput(true)
        advanceUntilIdle()

        assertFalse(environment.notificationPolicyWatched)
        assertFalse(viewModel.uiState.value.notificationPolicyPermissionPending)
    }

    @Test
    fun `悬浮窗未授权时置待授权并启动监控`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment(overlayGranted = false)
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        viewModel.setMiniPlayerEnabled(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.overlayPermissionPending)
        assertTrue(environment.overlayMonitorStarted)
    }

    @Test
    fun `从悬浮窗设置页返回仍未授权则回滚开关`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment(overlayGranted = false)
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        viewModel.setMiniPlayerEnabled(true)
        advanceUntilIdle()

        viewModel.onOverlaySettingsReturned()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.overlayPermissionPending)
        assertFalse(viewModel.uiState.value.playback.miniPlayerEnabled)
        assertTrue(environment.overlayMonitorStopped)
    }

    @Test
    fun `主动关闭迷你播放器会结束监控`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment()
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        viewModel.setMiniPlayerEnabled(false)
        advanceUntilIdle()

        assertTrue(environment.overlayMonitorStopped)
        assertFalse(viewModel.uiState.value.overlayPermissionPending)
    }

    @Test
    fun `语言切换交给环境应用并落库`() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val environment = FakeSettingsEnvironment()
        val viewModel = viewModel(repository, environment)

        advanceUntilIdle()
        viewModel.setLanguage(AppLanguage.CHINESE)
        advanceUntilIdle()

        assertEquals(AppLanguage.CHINESE, environment.appliedLanguage)
        assertEquals(AppLanguage.CHINESE, viewModel.uiState.value.language)
        assertEquals(AppLanguage.CHINESE, repository.getAppLanguage())
    }

    @Test
    fun `导入失败时置位提示`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment(
            importResult = ProxyImportMessage(text = "格式不支持", failed = true),
        )
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        viewModel.importProxySource("not-a-source")

        advanceUntilIdle()

        assertEquals("格式不支持", viewModel.uiState.value.proxyImportMessage)
        assertTrue(viewModel.uiState.value.proxyImportFailed)
    }

    @Test
    fun `清除导入提示后状态归零`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment(
            importResult = ProxyImportMessage(text = "格式不支持", failed = true),
        )
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        viewModel.importProxySource("not-a-source")
        advanceUntilIdle()

        viewModel.clearProxyImportMessage()

        assertNull(viewModel.uiState.value.proxyImportMessage)
        assertFalse(viewModel.uiState.value.proxyImportFailed)
    }

    @Test
    fun `外部存储变化会刷新代理列表`() = runTest(dispatcher) {
        val environment = FakeSettingsEnvironment()
        val viewModel = viewModel(environment = environment)

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.proxySources.isEmpty())

        environment.setSources(
            listOf(
                ProxySourceSpec(
                    name = "示例音源",
                    version = "1",
                    author = "",
                    homepage = "",
                    description = "",
                    platforms = emptyMap(),
                    rawJson = "{}",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.proxySources.size)
    }

    @Test
    fun `缓存占用在初始化时采样`() = runTest(dispatcher) {
        val viewModel = viewModel(environment = FakeSettingsEnvironment(cacheBytes = 2048L))

        advanceUntilIdle()

        assertEquals(2048L, viewModel.uiState.value.cacheTotalBytes)
    }
}
