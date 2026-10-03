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

// 竖屏播放列表面板占屏底一半，纵向滚动手势的起止位置须落在该面板内，
// 否则手势起点会落在面板外的遮罩上而被当作点击，直接收起面板
private const val PANEL_SCROLL_TOP = 0.55f
private const val PANEL_SCROLL_BOTTOM = 0.9f

// 竖屏播放器底部固定内容块（标题/艺术家/控制栏）只占屏底一部分：
// 艺术家信息行即位于该块内控制栏上方，纵向位置占屏高比例，按坐标点击进入其歌单
private const val ARTIST_INFO_Y = 0.82f
// 横屏播放器左侧封面视觉区中心占屏宽比例：点击封面进入 3D 封面轮播
private const val COVER_CENTER_X = 0.25f
// 横屏点右侧歌词区空白处：切换标题栏与控制栏显隐，用于轮播收起后重新唤出顶栏
private const val LYRICS_AREA_X = 0.8f

/**
 * 生成基准配置与启动配置，仅覆盖精简后的核心常用功能场景：
 *
 * - 冷启动到首页可交互
 * - 播放器页：上下滑动切歌、左右滑动切页、点击歌手信息查看歌手歌单
 * - 横竖屏切换与横屏 3D 封面轮播
 * - 歌单页：常听/专辑/歌手三智能歌单列表滚动
 * - 播放列表面板：列表滚动与切换排序后滚动
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
        prepare()
        startActivityAndWait()
        awaitHome()
    }

    /** 播放器页：上下滑动切歌、左右滑动切页、点击歌手信息查看该歌手歌单。 */
    @Test
    fun playerJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        // 上下滑动切歌：先下后上，两方向各覆盖一次切歌路径
        swipeOnPlayerVertical()
        // 左右滑动切页：依次进搜索页与歌单页再回到播放器，覆盖 Pager 吸附与页面组合
        swipeToPage(PAGE_SEARCH)
        swipeToPage(PAGE_PLAYER)
        swipeToPage(PAGE_PLAYLIST)
        swipeToPage(PAGE_PLAYER)
        // 点击播放器页歌手信息：进入该歌手的曲目列表，返回再切回播放器
        tapArtistInfo()
        device.pressBack()
        settle()
        swipeToPage(PAGE_PLAYER)
    }

    /** 横竖屏模式切换：竖屏进入横屏后点击封面进入 3D 轮播，收起后返回竖屏。 */
    @Test
    fun landscapeJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        // 竖屏唤出顶栏，点横屏按钮切到横屏布局
        revealTopBar()
        tap("home_landscape_mode")
        settle()
        // 横屏点击封面视觉区：进入 3D 封面轮播沉浸层
        tapCoverForCarousel()
        settle()
        // 系统返回键收起轮播
        device.pressBack()
        settle()
        // 轮播收起时顶栏随沉浸层隐藏：点右侧歌词区空白处重新唤出，再点横屏按钮切回竖屏
        device.click((device.displayWidth * LYRICS_AREA_X).toInt(), (device.displayHeight * 0.5f).toInt())
        settle()
        revealTopBar()
        tap("home_landscape_mode")
        settle()
    }

    /** 歌单页：常听、专辑、歌手三智能歌单列表滚动查看。 */
    @Test
    fun smartPlaylistJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        swipeToPage(PAGE_PLAYLIST)
        // 依次进入三智能歌单，滚动浏览后返回歌单总览
        listOf("playlist_smart_recent", "playlist_smart_album", "playlist_smart_artist").forEach {
            tap(it)
            scrollVertically(times = 2)
            device.pressBack()
            settle()
        }
        swipeToPage(PAGE_PLAYER)
    }

    /** 播放列表面板：列表滚动查看，切换排序后再滚动，最后恢复默认排序收起。 */
    @Test
    fun queueJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        tap("music_panel_playlist")
        // 播放列表滚动查看：手势收在面板内，避免起点落到遮罩上收起面板
        scrollVertically(times = 2, topFraction = PANEL_SCROLL_TOP, bottomFraction = PANEL_SCROLL_BOTTOM)
        scrollVertically(
            times = 2,
            reversed = true,
            topFraction = PANEL_SCROLL_TOP,
            bottomFraction = PANEL_SCROLL_BOTTOM,
        )
        // 切换排序（按标题）后再滚动，覆盖排序重组路径
        tap("music_panel_sort")
        tap("music_panel_sort_by_title")
        scrollVertically(times = 2, topFraction = PANEL_SCROLL_TOP, bottomFraction = PANEL_SCROLL_BOTTOM)
        // 恢复默认排序，避免改变队列顺序影响后续历程
        tap("music_panel_sort")
        tap("music_panel_sort_default")
        // 关闭面板收尾
        tap("home_player_close_playlist")
    }
}

/**
 * 授予应用所需的全部权限并清除后台，保证每个历程从已授权的冷启动状态开始。
 *
 * 应用在任一权限缺失时会随冷启动弹出权限对话框，它是独立窗口：弹出期间首页控件
 * 既不在无障碍树中也不接收点击，历程会整轮落空。因此这里须把会触发该对话框的权限
 * 一次性给全，而不只是俗称的「媒体与通知」几项。
 */
private fun MacrobenchmarkScope.prepare() {
    // 运行时权限直接授予；全部文件与悬浮窗走 appops，避免历程中途弹出系统授权页
    listOf(
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BLUETOOTH_CONNECT",
    ).forEach { device.executeShellCommand("pm grant $TARGET_PACKAGE $it") }
    device.executeShellCommand("appops set $TARGET_PACKAGE MANAGE_EXTERNAL_STORAGE allow")
    device.executeShellCommand("appops set $TARGET_PACKAGE SYSTEM_ALERT_WINDOW allow")
    // 电池优化白名单无 appops/pm 入口，只能加入 deviceidle 名单
    device.executeShellCommand("cmd deviceidle whitelist +$TARGET_PACKAGE")
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

/**
 * 点击竖屏播放器页的歌手信息行，进入该歌手的曲目列表。
 *
 * 艺术家文案随曲目动态变化，无法按固定资源匹配，故按坐标点击：
 * 该行位于屏底固定内容块内、控制栏上方，坐标占屏高比例取在其中部。
 */
private fun MacrobenchmarkScope.tapArtistInfo() {
    device.click(device.displayWidth / 2, (device.displayHeight * ARTIST_INFO_Y).toInt())
    settle()
}

/**
 * 点击横屏播放器左侧封面视觉区中心，进入 3D 封面轮播。
 *
 * 封面占左侧列的绝大部分并以中心对齐，点击该区域即触发轮播覆盖层。
 */
private fun MacrobenchmarkScope.tapCoverForCarousel() {
    device.click((device.displayWidth * COVER_CENTER_X).toInt(), (device.displayHeight * 0.5f).toInt())
    settle()
}

/**
 * 纵向滚动，用于触发惰性列表的组合与回收路径。
 *
 * [topFraction]、[bottomFraction] 是手势起止位置占屏高比例，正向为「从下往上」。
 * 滚动底部面板内的列表时须传入落在面板内的范围：起点越过面板顶缘会落到面板外的
 * 遮罩上，而遮罩以点击收起面板，滑动抬手会被当成点击，面板随之关闭。
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