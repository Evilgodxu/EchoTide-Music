package com.yichao.evilgodxu.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.yichao.evilgodxu"

// 首页并列页面的位置：枚举顺序即 Pager 索引，右滑退一页、左滑进一页
private const val PAGE_SEARCH = 0
private const val PAGE_PLAYER = 1
private const val PAGE_PLAYLIST = 2

// 首帧等待上限：低端机与模拟器冷启动都可能较慢，超时只影响兜底等待
private const val HOME_TIMEOUT_MS = 30_000L
// 单个控件出现等待上限
private const val UI_TIMEOUT_MS = 5_000L
// 动作之间的稳定间隔：等动画与状态落定，避免连续点击落在过渡态上
private const val SETTLE_MS = 700L
// 翻页滑动距离占屏宽比例：过短不足以触发 Pager 吸附
private const val SWIPE_SPAN = 0.7f

/**
 * 生成基准配置与启动配置。
 *
 * 启动路径单独成组并标记 `includeInStartupProfile`，其规则才会进入 Startup Profile；
 * 其余历程只进基准配置。把非启动历程混进启动配置会撑大首个 DEX、反而拖慢启动。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** 冷启动到首页可交互：Application 初始化、首页首帧组合与背景取色。 */
    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait()
        awaitHome()
    }

    /** 播放器页：顶部栏操作、播放列表面板开合、纵向切歌手势。 */
    @Test
    fun playerJourney() = rule.collect(TARGET_PACKAGE) {
        launch()
        tapDesc("music_panel_play_mode")
        tapDesc("music_panel_favorite")
        tapDesc("music_panel_timer_title")
        device.pressBack()
        settle()
        tapDesc("music_panel_playlist")
        settle()
        tapDesc("home_player_close_playlist")
        settle()
        swipeOnPlayerVertical()
    }

    /** 在线搜索页：历史清理、输入关键词、发起搜索。 */
    @Test
    fun searchJourney() = rule.collect(TARGET_PACKAGE) {
        launch()
        swipeToPage(PAGE_SEARCH)
        tapDesc("music_panel_search_history_clear")
        settle()
        typeQuery("yichao")
        device.pressEnter()
        settle()
    }

    /** 歌单页：列表滚动与面板内操作。 */
    @Test
    fun playlistJourney() = rule.collect(TARGET_PACKAGE) {
        launch()
        swipeToPage(PAGE_PLAYLIST)
        scrollVertically()
        tapDesc("playlist_scroll_to_top")
        settle()
        tapDesc("playlist_locate_playing")
        settle()
    }

    /** 设置页：进入、滚动浏览各分组、返回。 */
    @Test
    fun settingsJourney() = rule.collect(TARGET_PACKAGE) {
        launch()
        tapDesc("settings_title")
        settle()
        scrollVertically(times = 2)
        scrollVertically(reversed = true, times = 2)
        device.pressBack()
        settle()
    }
}

/** 启动应用并等待首页根节点出现，作为其余历程的统一起点。 */
private fun MacrobenchmarkScope.launch() {
    startActivityAndWait()
    awaitHome()
}

private fun MacrobenchmarkScope.awaitHome() {
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), HOME_TIMEOUT_MS)
    settle()
}

/**
 * 按无障碍标签点击控件。
 *
 * 标签在运行时从被测应用的资源里取，语言切换后依然命中，不依赖硬编码文案。
 * 控件缺失时静默跳过，保证单条历程失败不会让整轮采集中断。
 */
private fun MacrobenchmarkScope.tapDesc(resourceName: String): Boolean {
    val node = device.wait(Until.findObject(By.desc(label(resourceName))), UI_TIMEOUT_MS)
        ?: return false
    val bounds = node.visibleBounds
    if (bounds.width() <= 0 || bounds.height() <= 0) return false
    node.click()
    settle()
    return true
}

/** 横向翻页到指定索引，按当前位置判断方向。 */
private fun MacrobenchmarkScope.swipeToPage(target: Int) {
    val step = if (target > PAGE_PLAYER) -1 else 1
    repeat(kotlin.math.abs(target - PAGE_PLAYER)) {
        val width = device.displayWidth
        val height = device.displayHeight
        val from = width * (0.5f - step * SWIPE_SPAN / 2)
        val to = width * (0.5f + step * SWIPE_SPAN / 2)
        device.swipe(from.toInt(), height / 2, to.toInt(), height / 2, SWIPE_STEPS)
        settle()
    }
}

/** 播放器页纵向手势：切上一首/下一首。 */
private fun MacrobenchmarkScope.swipeOnPlayerVertical() {
    val width = device.displayWidth
    val height = device.displayHeight
    device.swipe(width / 2, (height * 0.65f).toInt(), width / 2, (height * 0.35f).toInt(), SWIPE_STEPS)
    settle()
    device.swipe(width / 2, (height * 0.35f).toInt(), width / 2, (height * 0.65f).toInt(), SWIPE_STEPS)
    settle()
}

/** 纵向滚动，用于触发惰性列表的组合与回收路径。 */
private fun MacrobenchmarkScope.scrollVertically(times: Int = 1, reversed: Boolean = false) {
    val width = device.displayWidth
    val height = device.displayHeight
    val (fromY, toY) = if (reversed) 0.3f to 0.7f else 0.7f to 0.3f
    repeat(times) {
        device.swipe(width / 2, (height * fromY).toInt(), width / 2, (height * toY).toInt(), SWIPE_STEPS)
        settle()
    }
}

/** 在搜索页输入关键词并触发一次搜索。 */
private fun MacrobenchmarkScope.typeQuery(query: String) {
    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS)
        ?: return
    field.click()
    settle()
    device.executeShellCommand("input text $query")
    settle()
}

/** 解析被测应用的字符串资源，取不到时退回资源名本身，至少仍给出可读的失败线索。 */
private fun label(resourceName: String): String {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val id = context.resources.getIdentifier(resourceName, "string", TARGET_PACKAGE)
    return if (id == 0) resourceName else context.getString(id)
}

private fun settle() {
    SystemClock.sleep(SETTLE_MS)
}

private const val SWIPE_STEPS = 24
