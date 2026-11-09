package com.yichao.evilgodxu

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
import org.junit.Before
import org.junit.Test

// 应用级状态持有者：验证仓库回流为初始状态，以及写入后经同一条路径回到 UI
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `仓库中的主题与语言回流为初始状态`() = runTest(dispatcher) {
        val viewModel = MainViewModel(
            settingsRepository = FakeSettingsRepository(
                themeMode = ThemeMode.DARK,
                language = AppLanguage.ENGLISH,
            ),
            appVersion = "1.0.0",
        )

        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
        assertEquals(AppLanguage.ENGLISH, viewModel.uiState.value.language)
        assertEquals("1.0.0", viewModel.uiState.value.version)
    }

    @Test
    fun `写入主题经仓库回流更新状态`() = runTest(dispatcher) {
        val viewModel = MainViewModel(
            settingsRepository = FakeSettingsRepository(),
            appVersion = "1.0.0",
        )

        advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, viewModel.uiState.value.themeMode)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `写入语言经仓库回流更新状态`() = runTest(dispatcher) {
        val viewModel = MainViewModel(
            settingsRepository = FakeSettingsRepository(),
            appVersion = "1.0.0",
        )

        advanceUntilIdle()
        assertEquals(AppLanguage.SYSTEM, viewModel.uiState.value.language)

        viewModel.setLanguage(AppLanguage.CHINESE)
        advanceUntilIdle()

        assertEquals(AppLanguage.CHINESE, viewModel.uiState.value.language)
    }
}
