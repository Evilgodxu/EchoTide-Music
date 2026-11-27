package com.yichao.evilgodxu.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

private const val TARGET_PACKAGE = "com.yichao.evilgodxu"
// logcat 过滤标签，核对历程命中
private const val TAG = "BaselineProfileGen"

// Pager 索引；右滑退一页、左滑进一页
private const val PAGE_SEARCH = 0
private const val PAGE_PLAYER = 1
private const val PAGE_PLAYLIST = 2

// 首帧等待上限，冷启动慢时兜底
private const val HOME_TIMEOUT_MS = 30_000L
// 单个控件出现等待上限
private const val UI_TIMEOUT_MS = 5_000L
// 旋转落定上限：等系统重新布局窗口
private const val ROTATION_TIMEOUT_MS = 10_000L
// 动作间隔，等动画落定
private const val SETTLE_MS = 700L
// 翻页滑动占屏宽比，过短不吸附
private const val SWIPE_SPAN = 0.7f
private const val SWIPE_STEPS = 24

// 面板占屏底一半，手势须落在面板内
private const val PANEL_SCROLL_TOP = 0.55f
private const val PANEL_SCROLL_BOTTOM = 0.9f

// 艺术家行距控制栏图标顶缘；锚定图标不按屏高比
private const val ARTIST_ROW_ABOVE_CONTROLS_DP = 89f

// 横屏封面视觉区中心占屏宽比，点击进轮播
private const val COVER_CENTER_X = 0.25f
// 横屏歌词区空白处：切换标题栏与控制栏
private const val LYRICS_AREA_X = 0.8f
private const val LYRICS_AREA_Y = 0.5f

// 沉浸标题栏高度：状态栏 + 顶栏
private const val TOP_APP_BAR_HEIGHT_DP = 64f
// 宽屏左栏宽度，限定左栏按名点击
private const val PLAYLIST_RAIL_WIDTH_DP = 300f
// 首行中心 52dp；行高 40dp，两形态均命中
private const val GROUP_ROW_CENTER_DP = 52f

/**
 * 生成基准与启动配置，覆盖核心场景：
 * - 冷启动到首页可交互
 * - 竖屏播放器：滑动切歌翻页、点艺术家
 * - 竖屏歌单：三类系统歌单下钻
 * - 播放列表面板：滚动与切换排序
 * - 横屏播放器：封面轮播与栏显隐
 * - 横屏歌单：左栏导航与分组下钻
 * 启动历程单独成组，混入非启动会撑大 DEX
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** 冷启动到首页可交互：初始化、首帧与取色 */
    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        prepare()
        startActivityAndWait()
        awaitHome()
    }

    /** 竖屏播放器：滑动切歌翻页、点艺术家 */
    @Test
    fun playerJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        // 上下滑动切歌，两方向各一次
        swipeOnPlayerVertical()
        // 左右翻页：搜索页、歌单页各往返一次
        swipeToPage(PAGE_SEARCH)
        swipeToPage(PAGE_PLAYER)
        swipeToPage(PAGE_PLAYLIST)
        swipeToPage(PAGE_PLAYER)
        // 点艺术家进歌手页，再逐级回退
        if (tapArtistRow()) {
            goBack()
            goBack()
        }
    }

    /** 竖屏歌单：总览与三类系统歌单下钻 */
    @Test
    fun portraitPlaylistJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        swipeToPage(PAGE_PLAYLIST)
        // 总览列表滚动：系统歌单卡片与我的歌单
        scrollVertically(times = 2)
        // 常听：曲目列表滚动后返回总览
        tap("playlist_smart_recent")
        scrollVertically(times = 2)
        goBack()
        // 专辑：滚动分组列表，进分组曲目再退回
        tap("playlist_smart_album")
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = false)
        scrollVertically(times = 2)
        goBack()
        goBack()
        // 歌手：进歌手页切换标签并滚动
        tap("playlist_smart_artist")
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = false)
        scrollVertically(times = 2)
        swipeArtistTab()
        scrollVertically(times = 2)
        goBack()
        goBack()
    }

    /** 播放列表面板：滚动、切排序、恢复 */
    @Test
    fun queueJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        openQueuePanel()
        // 手势收在面板内，避免点到遮罩
        scrollVertically(times = 2, topFraction = PANEL_SCROLL_TOP, bottomFraction = PANEL_SCROLL_BOTTOM)
        // 反向滚动：滑到顶会触发下拉收起，滑完核对面板
        scrollVertically(
            times = 2,
            reversed = true,
            topFraction = PANEL_SCROLL_TOP,
            bottomFraction = PANEL_SCROLL_BOTTOM,
        )
        openQueuePanel()
        // 切排序后再滚动
        tap("music_panel_sort")
        tap("music_panel_sort_by_title")
        scrollVertically(times = 2, topFraction = PANEL_SCROLL_TOP, bottomFraction = PANEL_SCROLL_BOTTOM)
        // 恢复默认排序，免影响后续历程
        tap("music_panel_sort")
        tap("music_panel_sort_default")
        // 关闭面板收尾
        tap("home_player_close_playlist")
    }

    /** 横屏播放器：封面轮播与切回竖屏 */
    @Test
    fun landscapePlayerJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        toLandscape()
        // 点封面进 3D 轮播，返回键收起
        tapCoverForCarousel()
        device.pressBack()
        settle()
        // 点歌词区唤出栏位，再切回竖屏
        toPortrait()
    }

    /** 横屏歌单：左栏导航与分组下钻 */
    @Test
    fun landscapePlaylistJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        toLandscape()
        swipeToPage(PAGE_PLAYLIST)
        // 左栏「常听」：右栏曲目列表滚动
        tap("playlist_smart_recent", maxLeftPx = railWidthPx())
        scrollVertically(times = 2)
        // 左栏「专辑」：滚动后下钻分组曲目
        tap("playlist_smart_album", maxLeftPx = railWidthPx())
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = true)
        scrollVertically(times = 2)
        goBack()
        // 左栏「歌手」：滚动后下钻歌手曲目
        tap("playlist_smart_artist", maxLeftPx = railWidthPx())
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = true)
        scrollVertically(times = 2)
        goBack()
        // 收起歌单面板回播放器页
        goBack()
    }
}

/** 授全所需权限并清后台；缺任一权限会弹窗挡住历程 */
private fun MacrobenchmarkScope.prepare() {
    // 权限直接授予，文件与悬浮窗走 appops
    listOf(
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BLUETOOTH_CONNECT",
    ).forEach { device.executeShellCommand("pm grant $TARGET_PACKAGE $it") }
    device.executeShellCommand("appops set $TARGET_PACKAGE MANAGE_EXTERNAL_STORAGE allow")
    device.executeShellCommand("appops set $TARGET_PACKAGE SYSTEM_ALERT_WINDOW allow")
    device.executeShellCommand("am force-stop $TARGET_PACKAGE")
}

/** 启动并等首页根节点，作为统一起点 */
private fun MacrobenchmarkScope.launch() {
    startActivityAndWait()
    awaitHome()
}

private fun MacrobenchmarkScope.awaitHome() {
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), HOME_TIMEOUT_MS)
    settle()
}

/**
 * 竖屏切横屏：唤出标题栏点横屏，直到实际转横。
 * 栏两秒后自动收起，故成组重试。
 */
private fun MacrobenchmarkScope.toLandscape() {
    repeat(RETRY_TIMES) {
        revealTopBar()
        if (tap("home_landscape_mode") && awaitRotation(landscape = true)) return
    }
    Log.w(TAG, "MISS switch to landscape")
}

/**
 * 横屏切竖屏：点歌词区唤出标题栏再点横屏，直到转为纵向。
 * 顶栏未唤出则重试；部分模拟器不回落时改写显示旋转。
 */
private fun MacrobenchmarkScope.toPortrait() {
    var attempt = 0
    var delivered = false
    while (attempt < RETRY_TIMES && !delivered) {
        showLandscapeChrome()
        delivered = tap("home_landscape_mode")
        if (delivered && awaitRotation(landscape = false)) return
        attempt++
    }
    device.executeShellCommand("wm user-rotation lock 0")
    val rotated = awaitRotation(landscape = false)
    device.executeShellCommand("wm user-rotation free")
    if (!rotated) Log.w(TAG, "MISS switch to portrait")
}

/** 点播放器页顶部唤出标题栏；该处为封面区 */
private fun MacrobenchmarkScope.revealTopBar() {
    device.click(device.displayWidth / 2, (device.displayHeight * 0.07f).toInt())
    settle()
}

/** 点歌词区空白处切换标题栏与控制栏显隐 */
private fun MacrobenchmarkScope.showLandscapeChrome() {
    device.click(
        (device.displayWidth * LYRICS_AREA_X).toInt(),
        (device.displayHeight * LYRICS_AREA_Y).toInt(),
    )
    settle()
}

/** 以窗口实测宽高判断朝向落定，超时视为切换未生效 */
private fun MacrobenchmarkScope.awaitRotation(landscape: Boolean): Boolean {
    val deadline = SystemClock.uptimeMillis() + ROTATION_TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
        if ((device.displayWidth > device.displayHeight) == landscape) {
            settle()
            return true
        }
        SystemClock.sleep(ROTATION_POLL_MS)
    }
    Log.w(TAG, "rotation timeout, landscape=$landscape")
    return false
}

/** 返回键逐级退出面板层级与页面 */
private fun MacrobenchmarkScope.goBack() {
    device.pressBack()
    settle()
}

/**
 * 按无障碍标签或可见文本点击，失败只记日志不中断。
 * maxLeftPx 限定左栏取值，避开右栏同名标题。
 */
private fun MacrobenchmarkScope.tap(resourceName: String, maxLeftPx: Int = Int.MAX_VALUE): Boolean {
    val label = label(resourceName)
    val node = findFirst(By.desc(label), maxLeftPx) ?: findFirst(By.text(label), maxLeftPx)
    return clickIfVisible(node, "$resourceName($label)")
}

/** 等文案出现确认页面，只打点 */
private fun MacrobenchmarkScope.awaitText(resourceName: String): Boolean {
    val label = label(resourceName)
    val found = device.wait(Until.hasObject(By.text(label)), UI_TIMEOUT_MS)
    Log.i(TAG, if (found) "SHOWN $resourceName($label)" else "MISS $resourceName($label)")
    return found
}

/** 等标签出现确认展开态，只打点 */
private fun MacrobenchmarkScope.awaitDesc(resourceName: String): Boolean {
    val label = label(resourceName)
    val found = device.wait(Until.hasObject(By.desc(label)), UI_TIMEOUT_MS)
    Log.i(TAG, if (found) "SHOWN $resourceName($label)" else "MISS $resourceName($label)")
    return found
}

/**
 * 确保播放列表面板展开：以常驻的排序按钮判定。
 * 面板可能被下拉收起或点击落在动画过渡态，故重试。
 */
private fun MacrobenchmarkScope.openQueuePanel(): Boolean {
    if (queuePanelShown()) return true
    repeat(RETRY_TIMES) {
        if (tap("music_panel_playlist") && awaitDesc("music_panel_sort")) return true
    }
    Log.w(TAG, "MISS open queue panel")
    return false
}

private fun MacrobenchmarkScope.queuePanelShown(): Boolean =
    device.findObject(By.desc(label("music_panel_sort"))) != null

/** 取首个左边界在 maxLeftPx 内的节点，区分两栏同名 */
private fun MacrobenchmarkScope.findFirst(selector: BySelector, maxLeftPx: Int): UiObject2? {
    val nodes = device.wait(Until.findObjects(selector), UI_TIMEOUT_MS) ?: return null
    return nodes.firstOrNull { node ->
        runCatching { node.visibleBounds.left <= maxLeftPx }.getOrDefault(false)
    }
}

/**
 * 校验节点可见可交互再点；遮挡或置灰只会空耗一步。
 * 重组会让节点引用失效，故在此吞掉过期异常。
 */
private fun clickIfVisible(node: UiObject2?, hint: String): Boolean {
    if (node == null) {
        Log.i(TAG, "MISS $hint")
        settle()
        return false
    }
    // 读取边界与点击都可能抛过期异常，整段兜住
    return try {
        val bounds = node.visibleBounds
        when {
            bounds.width() <= 0 || bounds.height() <= 0 -> {
                Log.i(TAG, "INVISIBLE $hint $bounds")
                settle()
                false
            }
            // 标题栏收起时控件置灰不可点
            !node.isEnabled -> {
                Log.i(TAG, "DISABLED $hint $bounds")
                settle()
                false
            }
            else -> {
                Log.i(TAG, "HIT $hint $bounds")
                node.click()
                settle()
                true
            }
        }
    } catch (error: StaleObjectException) {
        Log.i(TAG, "STALE $hint", error)
        settle()
        false
    }
}

/** 横滑翻页到指定索引，按位置定方向 */
private fun MacrobenchmarkScope.swipeToPage(target: Int) {
    val step = if (target > PAGE_PLAYER) -1 else 1
    repeat(abs(target - PAGE_PLAYER)) {
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

/** 由控制栏反推艺术家行位置点击；未命中只日志 */
private fun MacrobenchmarkScope.tapArtistRow(): Boolean {
    val anchor = device.wait(Until.findObject(By.desc(label("music_panel_playlist"))), UI_TIMEOUT_MS)
    val anchorTop = runCatching { anchor?.visibleBounds?.top }.getOrNull()
    if (anchorTop == null || anchorTop <= 0) {
        Log.i(TAG, "MISS artist row anchor")
        settle()
        return false
    }
    val y = anchorTop - dpPx(ARTIST_ROW_ABOVE_CONTROLS_DP)
    Log.i(TAG, "TAP artist row x=${device.displayWidth / 2} y=$y anchor=$anchorTop")
    device.click(device.displayWidth / 2, y)
    settle()
    return awaitText("playlist_tab_songs")
}

/** 点横屏封面区中心进 3D 轮播 */
private fun MacrobenchmarkScope.tapCoverForCarousel() {
    device.click((device.displayWidth * COVER_CENTER_X).toInt(), (device.displayHeight * 0.5f).toInt())
    settle()
}

/** 按坐标点分组首行下钻；未命中只日志 */
private fun MacrobenchmarkScope.tapFirstGroupRow(expanded: Boolean) {
    val width = device.displayWidth
    val centerXDp = if (expanded) {
        // 宽屏右栏内容水平居中
        PLAYLIST_RAIL_WIDTH_DP + (width / density() - PLAYLIST_RAIL_WIDTH_DP) / 2f
    } else {
        width / density() / 2f
    }
    // 窄屏内容自标题栏下方开始
    val contentTopPx = if (expanded) 0 else statusBarHeightPx() + dpPx(TOP_APP_BAR_HEIGHT_DP)
    val y = contentTopPx + dpPx(GROUP_ROW_CENTER_DP)
    Log.i(TAG, "TAP group row first(expanded=$expanded) x=${dpPx(centerXDp)} y=$y")
    device.click(dpPx(centerXDp), y)
    settle()
    awaitText("playlist_play_all")
}

/**
 * 歌手页左滑切「专辑」；「歌曲」右滑回退。
 */
private fun MacrobenchmarkScope.swipeArtistTab() {
    val width = device.displayWidth
    val height = device.displayHeight
    device.swipe(
        (width * 0.8f).toInt(),
        (height * 0.6f).toInt(),
        (width * 0.2f).toInt(),
        (height * 0.6f).toInt(),
        SWIPE_STEPS,
    )
    settle()
}

/**
 * 纵向滚动以触发惰性列表的组合与回收。
 * 面板内须传面板内范围：起点越过顶缘会点到遮罩收起面板。
 */
private fun MacrobenchmarkScope.scrollVertically(
    times: Int = 1,
    reversed: Boolean = false,
    topFraction: Float = 0.3f,
    bottomFraction: Float = 0.7f,
) {
    val width = device.displayWidth
    val height = device.displayHeight
    val (fromY, toY) = if (reversed) topFraction to bottomFraction else bottomFraction to topFraction
    repeat(times) {
        device.swipe(width / 2, (height * fromY).toInt(), width / 2, (height * toY).toInt(), SWIPE_STEPS)
        settle()
    }
}

/** 按实测密度把 dp 换成像素 */
private fun density(): Float = appContext().resources.displayMetrics.density

/** 状态栏高度：沉浸标题栏预留同一基准 */
private fun statusBarHeightPx(): Int {
    val resources = appContext().resources
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

private fun dpPx(dp: Float): Int = (dp * density()).toInt()

/** 左栏宽度像素，限定左栏点击 */
private fun railWidthPx(): Int = dpPx(PLAYLIST_RAIL_WIDTH_DP)

/** 从被测包上下文解析文案，插桩上下文常指向测试包 */
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

/** 被测包上下文，与插桩自身包无关 */
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

// 成组重试次数，落空即重新唤出
private const val RETRY_TIMES = 3
// 朝向落定的轮询间隔
private const val ROTATION_POLL_MS = 100L