package com.yichao.evilgodxu.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.yichao.evilgodxu"
// 采集过程打点标签：生成结束后从 logcat 过滤该标签核对各历程的实际命中情况
private const val TAG = "BaselineProfileGen"

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

// 播放列表面板只占屏幕下半部：列表行与面板底部搜索框的纵向位置占屏高比例
private const val PANEL_LIST_ROW_Y = 0.75f
private const val PANEL_SEARCH_BAR_Y = 0.97f

/**
 * 生成基准配置与启动配置，覆盖应用的常用功能场景。
 *
 * 启动路径单独成组并标记 `includeInStartupProfile`，其规则才会进入 Startup Profile；
 * 其余历程只进基准配置。把非启动历程混进启动配置会撑大首个 DEX、反而拖慢启动。
 *
 * 场景与控件的对应关系：
 * - 播放器页：播放模式、切歌、播放暂停、收藏、定时对话框（顶栏需先触摸唤出）
 * - 播放列表面板：滚动、定位播放、回到顶部、列表内搜索、排序、行侧滑高级菜单与频谱页
 * - 在线搜索页：历史清理、关键词搜索、结果滚动
 * - 歌单页：总览滚动、系统歌单四个分组、新建歌单对话框
 * - 设置页：主题/语言对话框、播放开关、排版页、存储管理与缓存清理、黑名单重置
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
        prepare()
        startActivityAndWait()
        awaitHome()
    }

    /** 播放器页：播放模式循环、切歌手势、播放暂停，再唤出顶栏操作收藏与定时对话框。 */
    @Test
    fun playerJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        repeat(3) { tap("music_panel_play_mode") }
        tap("home_player_previous")
        tap("home_player_next")
        // 冷启动即处于播放态：先暂停再播放，两态各覆盖一次，否则播放按钮在播放态下不存在
        tap("music_panel_pause")
        tap("music_panel_play")
        swipeOnPlayerVertical()
        // 顶栏两秒无操作自动收起，每次操作顶栏控件前都要重新唤出
        revealTopBar()
        // 定时对话框开合：覆盖对话框组合路径
        tap("music_panel_timer_title")
        tap("music_panel_timer_cancel")
        revealTopBar()
        // 收藏两次回到未收藏态，避免状态残留影响后续历程
        tap("music_panel_favorite")
        revealTopBar()
        tap("music_panel_favorite")
    }

    /** 播放列表面板：滚动、定位播放、回到顶部、列表内搜索、排序切换与高级菜单频谱页。 */
    @Test
    fun queueJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        tap("music_panel_playlist")
        // 面板内的搜索框与悬浮按钮在滚动中或滚到底部时会隐藏，相关步骤必须排在滚动之前；
        // 向下滑动的起手点落在面板外的遮罩上会被判成点击收起面板，因此只做上滑滚动
        tap("playlist_locate_playing")
        tap("playlist_scroll_to_top")
        scrollVertically(times = 2)
        // 列表行右滑触发高级菜单，进入频谱页后返回；频谱页是独立目的地，
        // 返回后首页面板状态重建、面板已收起，故这一段结束后重新展开面板再继续
        swipeFirstRowRight()
        tap("playlist_advanced_menu_spectrum")
        device.pressBack()
        settle()
        tap("music_panel_playlist")
        // 排序切换后恢复默认，避免改变队列顺序影响后续历程
        tap("music_panel_sort")
        tap("music_panel_sort_by_title")
        tap("music_panel_sort")
        tap("music_panel_sort_default")
        // 关闭按钮：聚焦搜索框会张开软键盘并挤压面板，关闭按钮随之不可点，故排在列表内搜索之前
        tap("home_player_close_playlist")
        settle()
        // 重新展开面板收尾：列表内搜索会聚焦输入框并张开软键盘，之后不再安排其它动作
        tap("music_panel_playlist")
        typePanelQuery("a")
    }

    /** 在线搜索页：历史清理、输入关键词、发起搜索与结果滚动。 */
    @Test
    fun searchJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        swipeToPage(PAGE_SEARCH)
        tap("music_panel_search_history_clear")
        settle()
        typeQuery("yichao")
        device.pressEnter()
        settle()
        scrollVertically(times = 2)
        swipeToPage(PAGE_PLAYER)
    }

    /** 歌单页：总览滚动、系统歌单各分组进出、新建歌单对话框。 */
    @Test
    fun playlistJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        swipeToPage(PAGE_PLAYLIST)
        scrollVertically(times = 2)
        scrollVertically(times = 2, reversed = true)
        tap("playlist_smart_recent")
        tap("back")
        tap("playlist_smart_favorite")
        tap("back")
        tap("playlist_smart_album")
        tap("back")
        tap("playlist_smart_artist")
        tap("back")
        // 新建歌单对话框开合，返回键收起不做实际创建
        tap("playlist_create")
        device.pressBack()
        settle()
        swipeToPage(PAGE_PLAYER)
    }

    /** 设置页：主题与语言对话框、播放开关、排版页、存储管理与缓存清理、黑名单重置。 */
    @Test
    fun settingsJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        revealTopBar()
        tap("settings_title")
        settle()
        // 主题对话框：每次选择后对话框关闭，再次打开换一档，覆盖三档组合路径
        repeat(3) {
            tapSettingsEntry("settings_theme_title")
            tap(arrayOf("theme_dark", "theme_light", "theme_system")[it])
        }
        // 语言对话框：选择中文即关闭
        tapSettingsEntry("settings_language_title")
        tap("language_chinese")
        // 播放分组开关逐屏切换并当场切回：既覆盖 Switch 开与关两组组合路径，又不残留设置变更
        toggleSwitches()
        scrollVertically()
        toggleSwitches()
        scrollVertically(reversed = true)
        toggleSwitches()
        // 排版页：滚动浏览后返回
        tapSettingsEntry("settings_typography_title")
        scrollVertically(times = 2)
        device.pressBack()
        settle()
        // 存储管理页：滚动后执行一次缓存清理，再返回
        tapSettingsEntry("settings_cache_entry_title")
        scrollVertically(times = 2)
        tap("cache_clear")
        device.pressBack()
        settle()
        // 黑名单重置：只打开确认对话框并取消，不做实际重置
        tapSettingsEntry("settings_blacklist_reset_title")
        tap("settings_blacklist_reset_cancel")
        // 回到首页
        device.pressBack()
        settle()
    }
}

/** 授予应用所需的全部权限并清除后台，保证每个历程从已授权的冷启动状态开始。 */
private fun MacrobenchmarkScope.prepare() {
    // 运行时权限直接授予；全部文件与悬浮窗走 appops，避免历程中途弹出系统授权页
    listOf(
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.POST_NOTIFICATIONS",
    ).forEach { device.executeShellCommand("pm grant $TARGET_PACKAGE $it") }
    device.executeShellCommand("appops set $TARGET_PACKAGE MANAGE_EXTERNAL_STORAGE allow")
    device.executeShellCommand("appops set $TARGET_PACKAGE SYSTEM_ALERT_WINDOW allow")
    device.executeShellCommand("am force-stop $TARGET_PACKAGE")
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

/** 触摸播放器页顶部唤出自动收起的标题栏：落在标题栏中部空白处，不触发任何控件。 */
private fun MacrobenchmarkScope.revealTopBar() {
    device.click(device.displayWidth / 2, (device.displayHeight * 0.07f).toInt())
    settle()
}

/**
 * 按无障碍标签或可见文本点击控件。
 *
 * 图标类控件以标签匹配，纯文本入口以可见文本匹配，命中失败仅记录日志并跳过，
 * 保证单条操作失败不会让整轮采集中断。
 */
private fun MacrobenchmarkScope.tap(resourceName: String): Boolean {
    val label = label(resourceName)
    val node = device.wait(Until.findObject(By.desc(label)), UI_TIMEOUT_MS)
        ?: device.wait(Until.findObject(By.text(label)), UI_TIMEOUT_MS)
    return clickIfVisible(node, "$resourceName($label)")
}

/**
 * 点击设置页入口行。
 *
 * 设置页的分组标题与入口标题可能同名（如「语言」），按文本匹配会先命中不可点的分组标题，
 * 因此取同名命中项里面积最大者，即真实可点的入口行。
 */
private fun MacrobenchmarkScope.tapSettingsEntry(resourceName: String): Boolean {
    val label = label(resourceName)
    device.wait(Until.hasObject(By.text(label)), UI_TIMEOUT_MS)
    val node = runCatching {
        device.findObjects(By.text(label))
            .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
    }.getOrNull()
    return clickIfVisible(node, "text:$resourceName($label)")
}

/**
 * 把当前屏幕上可见的 Switch 各点两遍：第一遍全部切换，第二遍全部切回。
 *
 * 每轮重新查找节点，避免上一轮点击触发的重组让引用过期；
 * 滚动途经各屏执行一遍即可覆盖全部开关，同时保证不残留设置变更。
 */
private fun MacrobenchmarkScope.toggleSwitches() {
    repeat(2) {
        // 开关是 Compose 自绘控件，无障碍类名并非 android.widget.Switch，只能按可勾选状态匹配；
        // 取到引用后仍可能被重组刷新，排序键读不到时按 0 处理，交给点击环节兜底
        device.findObjects(By.checkable(true))
            .sortedBy { runCatching { it.visibleBounds.top }.getOrDefault(0) }
            .forEachIndexed { index, node -> clickIfVisible(node, "switch#$index") }
    }
}

/**
 * 校验节点可见后再点击：遮挡或移出屏幕的节点不响应点击，命中遮挡节点只会空耗一步。
 *
 * 点击前后状态可能被应用重组刷新，节点引用随时可能失效，
 * 统一在这里吞掉过期异常并记日志，不让单次操作中断整轮采集。
 */
private fun clickIfVisible(node: UiObject2?, hint: String): Boolean {
    if (node == null) {
        Log.i(TAG, "MISS $hint")
        settle()
        return false
    }
    // 读取边界与点击都可能因重组让引用失效而抛 StaleObjectException，
    // 整段兜住并记日志，避免单次失效中断整轮采集
    return try {
        val bounds = node.visibleBounds
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.i(TAG, "INVISIBLE $hint $bounds")
            settle()
            false
        } else {
            Log.i(TAG, "HIT $hint $bounds")
            node.click()
            settle()
            true
        }
    } catch (error: StaleObjectException) {
        Log.i(TAG, "STALE $hint", error)
        settle()
        false
    }
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

/** 播放列表行右滑：露出并触发高级菜单动作。 */
private fun MacrobenchmarkScope.swipeFirstRowRight() {
    val width = device.displayWidth
    val height = device.displayHeight
    // 面板只占屏幕下半部，纵坐标需落在面板内的曲目行上：取面板中部，避开顶部标题行与底部搜索框；
    // 方向由左向右才是面板定义的「右滑」，反向会触发左滑拉黑而非高级菜单
    val rowY = (height * PANEL_LIST_ROW_Y).toInt()
    device.swipe((width * 0.1f).toInt(), rowY, (width * 0.9f).toInt(), rowY, SWIPE_STEPS)
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

/** 在第一个输入框中输入关键词：覆盖搜索框聚焦、软键盘路径与输入过滤重组。 */
private fun MacrobenchmarkScope.typeQuery(query: String) {
    val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS)
        ?: return
    field.click()
    settle()
    device.executeShellCommand("input text $query")
    settle()
}

/**
 * 在播放列表面板的搜索框内输入关键词。
 *
 * 面板搜索框贴着面板底边，按坐标点击即可聚焦；并列的其它页面常驻合成树，
 * 按类名取第一个输入框会落到别的页面上，因此这里不能用 typeQuery。
 */
private fun MacrobenchmarkScope.typePanelQuery(query: String) {
    device.click(device.displayWidth / 2, (device.displayHeight * PANEL_SEARCH_BAR_Y).toInt())
    settle()
    device.executeShellCommand("input text $query")
    settle()
}

/**
 * 从被测应用解析字符串文案。
 *
 * 插桩的 targetContext 在部分环境下指向测试包而非目标包，按名查找会全部落空，
 * 这里统一改用 createPackageContext 拿目标包资源后按名查 ID。
 */
private fun label(resourceName: String): String {
    val context = appContext()
    val id = context.resources.getIdentifier(resourceName, "string", TARGET_PACKAGE)
    if (id == 0) {
        Log.w(TAG, "resolve string $resourceName failed, ctx=${context.packageName}")
        return resourceName
    }
    return runCatching { context.getString(id) }.getOrElse {
        Log.w(TAG, "load string $resourceName failed", it)
        resourceName
    }
}

/** 目标包上下文：文案与资源一律从被测应用读取，与插桩自身的包无关。 */
private fun appContext() = runCatching {
    InstrumentationRegistry.getInstrumentation().targetContext
        .createPackageContext(TARGET_PACKAGE, 0)
}.getOrElse {
    Log.w(TAG, "create package context failed", it)
    InstrumentationRegistry.getInstrumentation().targetContext
}

private fun settle() {
    SystemClock.sleep(SETTLE_MS)
}

private const val SWIPE_STEPS = 24
