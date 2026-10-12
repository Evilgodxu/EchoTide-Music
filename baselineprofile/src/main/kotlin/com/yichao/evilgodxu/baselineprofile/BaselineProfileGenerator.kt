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
// 旋转落定等待上限：请求方向后系统需重新布局窗口并分发配置变更
private const val ROTATION_TIMEOUT_MS = 10_000L
// 动作之间的稳定间隔：等动画与状态落定，避免连续点击落在过渡态上
private const val SETTLE_MS = 700L
// 翻页滑动距离占屏宽比例：过短不足以触发 Pager 吸附
private const val SWIPE_SPAN = 0.7f
private const val SWIPE_STEPS = 24

// 竖屏播放列表面板占屏底一半，纵向滚动手势的起止位置须落在该面板内，
// 否则手势起点会落在面板外的遮罩上而被当作点击，直接收起面板
private const val PANEL_SCROLL_TOP = 0.55f
private const val PANEL_SCROLL_BOTTOM = 0.9f

// 竖屏播放器艺术家行距底部控制栏图标顶缘的高度。
// 曲名、艺术家、进度区与控制栏自上而下贴屏底排列，各行高度固定，故艺术家行与控制栏的距离恒定；
// 锚定控制栏（图标有稳定的无障碍文案）而非屏高比例——比例会随窗口高宽比与底部系统栏避让量漂移
private const val ARTIST_ROW_ABOVE_CONTROLS_DP = 89f

// 横屏播放器左侧封面视觉区中心占屏宽比例：点击封面进入 3D 封面轮播
private const val COVER_CENTER_X = 0.25f
// 横屏点右侧歌词区空白处：切换标题栏与控制栏显隐，用于轮播收起后重新唤出顶栏
private const val LYRICS_AREA_X = 0.8f
private const val LYRICS_AREA_Y = 0.5f

// 沉浸标题栏高度：首页竖屏按状态栏高度预留 + Material 顶栏，宽屏无标题栏
private const val TOP_APP_BAR_HEIGHT_DP = 64f
// 宽屏歌单面板左栏宽度（PlaylistRailWidth）。左栏导航项与右栏标题同名（「常听」「专辑」等），
// 按文案点击时限定在该宽度内取值，避免命中右栏标题
private const val PLAYLIST_RAIL_WIDTH_DP = 300f
// 歌单分组列表首行的中心距内容顶部的高度：窄屏在页面标题（34dp）之下、宽屏在右栏标题（30dp）之下，
// 行高 40dp，取 52dp 两形态都落在首行内。分组名取自曲库、无法按固定文案匹配，只能按坐标点击
private const val GROUP_ROW_CENTER_DP = 52f

/**
 * 生成基准配置与启动配置，覆盖精简后的核心常用功能场景：
 *
 * - 冷启动到首页可交互
 * - 竖屏播放器页：上下滑动切歌、左右滑动翻页、点按艺术家信息进入该歌手歌单
 * - 竖屏歌单页：总览滚动、常听曲目、专辑/歌手分组与分组曲目、歌手页歌曲/专辑标签切换
 * - 竖屏播放列表面板：列表滚动与切换排序
 * - 横屏播放器页：3D 封面轮播、标题栏与控制栏显隐
 * - 横屏歌单页：宽屏左栏导航下的系统歌单与分组下钻
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

    /** 竖屏播放器页：上下滑动切歌、左右滑动翻页、点按艺术家信息查看该歌手歌单。 */
    @Test
    fun playerJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        // 上下滑动切歌：先上后下，两方向各覆盖一次切歌路径
        swipeOnPlayerVertical()
        // 左右滑动切页：依次进搜索页与歌单页再回到播放器，覆盖 Pager 吸附与页面组合
        swipeToPage(PAGE_SEARCH)
        swipeToPage(PAGE_PLAYER)
        swipeToPage(PAGE_PLAYLIST)
        swipeToPage(PAGE_PLAYER)
        // 点按播放器页的艺术家信息：进入该歌手的曲目列表（歌手页 → 总览 → 播放器逐级回退）
        if (tapArtistRow()) {
            goBack()
            goBack()
        }
    }

    /** 竖屏歌单页：总览与三类系统歌单（常听、专辑分组、歌手分组）的列表与分组曲目。 */
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
        // 专辑：分组列表滚动后进入某分组的曲目页，再逐级退回总览
        tap("playlist_smart_album")
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = false)
        scrollVertically(times = 2)
        goBack()
        goBack()
        // 歌手：分组列表滚动后进入沉浸歌手页，切换歌曲/专辑标签并滚动
        tap("playlist_smart_artist")
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = false)
        scrollVertically(times = 2)
        swipeArtistTab()
        scrollVertically(times = 2)
        goBack()
        goBack()
    }

    /** 竖屏播放列表面板：列表滚动查看，切换排序后再滚动，最后恢复默认排序收起。 */
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

    /** 横屏播放器页：点击封面进入 3D 封面轮播，收起后经标题栏切回竖屏。 */
    @Test
    fun landscapePlayerJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        toLandscape()
        // 横屏点击封面视觉区：进入 3D 封面轮播沉浸层，系统返回键收起
        tapCoverForCarousel()
        device.pressBack()
        settle()
        // 点歌词区空白处唤出标题栏与控制栏，再点横屏按钮切回竖屏
        toPortrait()
    }

    /** 横屏歌单页：宽屏左栏导航下的系统歌单切换与专辑/歌手分组下钻。 */
    @Test
    fun landscapePlaylistJourney() = rule.collect(TARGET_PACKAGE) {
        prepare()
        launch()
        toLandscape()
        swipeToPage(PAGE_PLAYLIST)
        // 左栏「常听」：右栏曲目列表滚动
        tap("playlist_smart_recent", maxLeftPx = railWidthPx())
        scrollVertically(times = 2)
        // 左栏「专辑」：分组列表滚动后下钻到某分组的曲目
        tap("playlist_smart_album", maxLeftPx = railWidthPx())
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = true)
        scrollVertically(times = 2)
        goBack()
        // 左栏「歌手」：分组列表滚动后下钻到该歌手的曲目
        tap("playlist_smart_artist", maxLeftPx = railWidthPx())
        scrollVertically(times = 2)
        tapFirstGroupRow(expanded = true)
        scrollVertically(times = 2)
        goBack()
        // 收起歌单面板回到播放器页（本页由首页的返回处理接管）
        goBack()
    }
}

/**
 * 授予应用所需的全部权限并清除后台，保证每个历程从已授权的冷启动状态开始。
 *
 * 应用在任一权限缺失时会随冷启动弹出权限对话框，它是独立窗口：弹出期间首页控件
 * 既不在无障碍树中也不接收点击，历程会整轮落空。故这里须把会触发该对话框的权限
 * 一次性给全（全部文件、音乐、图片、蓝牙、通知、电池优化白名单），而不只是俗称的「媒体与通知」几项。
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

/**
 * 竖屏切横屏：触摸播放器页唤出自动收起的标题栏，再点横屏按钮，直到窗口实际转为横向。
 *
 * 标题栏两秒无操作即自动收起、控件随之不可交互，故按「唤出—点击—核对朝向」成组重试，
 * 单次点击落空不会让整个历程停在竖屏。
 */
private fun MacrobenchmarkScope.toLandscape() {
    repeat(RETRY_TIMES) {
        revealTopBar()
        if (tap("home_landscape_mode") && awaitRotation(landscape = true)) return
    }
    Log.w(TAG, "MISS switch to landscape")
}

/**
 * 横屏切竖屏：点歌词区唤出悬浮标题栏后点横屏按钮，直到窗口实际转为纵向。
 *
 * 点击落空多为顶栏未唤出（重新唤出再试）；点击已送达而朝向未变，则是横屏下应用把方向请求改回
 * 「不指定」后系统未按传感器回落显示（部分模拟器如此，重试无益）。此时把显示旋转临时改写为
 * 自然方向再解锁，使采集继续走横屏之外的其余步骤。
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

/** 触摸播放器页顶部唤出自动收起的标题栏：该处为封面区域，单击不触发任何控件。 */
private fun MacrobenchmarkScope.revealTopBar() {
    device.click(device.displayWidth / 2, (device.displayHeight * 0.07f).toInt())
    settle()
}

/** 点横屏歌词区空白处唤出悬浮标题栏与控制栏（横屏两侧均不常驻，点按即切换显隐）。 */
private fun MacrobenchmarkScope.showLandscapeChrome() {
    device.click(
        (device.displayWidth * LYRICS_AREA_X).toInt(),
        (device.displayHeight * LYRICS_AREA_Y).toInt(),
    )
    settle()
}

/**
 * 等待窗口朝向落定：请求方向后系统需重新布局，以窗口实测宽高判定而非固定等待。
 * 朝向未按预期落定即视为本次切换未生效，交由调用方重试。
 */
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

/** 系统返回键逐级回退：歌单面板的下钻层级、面板自身与首页页面栈都经它退出。 */
private fun MacrobenchmarkScope.goBack() {
    device.pressBack()
    settle()
}

/**
 * 按无障碍标签或可见文本点击控件。
 *
 * 图标类控件以标签匹配，纯文本入口以可见文本匹配，命中失败仅记录日志并跳过，
 * 保证单条操作失败不会让整轮采集中断。
 *
 * [maxLeftPx] 限定候选取用范围：宽屏歌单左栏导航项与右栏标题同名，不限定时会命中同名的首个节点，
 * 左栏导航一律按该参数限定在左栏内取值。
 */
private fun MacrobenchmarkScope.tap(resourceName: String, maxLeftPx: Int = Int.MAX_VALUE): Boolean {
    val label = label(resourceName)
    val node = findFirst(By.desc(label), maxLeftPx) ?: findFirst(By.text(label), maxLeftPx)
    return clickIfVisible(node, "$resourceName($label)")
}

/** 等待文案出现，用于确认已进入目标页面：只打点核对，不改变采集流程。 */
private fun MacrobenchmarkScope.awaitText(resourceName: String): Boolean {
    val label = label(resourceName)
    val found = device.wait(Until.hasObject(By.text(label)), UI_TIMEOUT_MS)
    Log.i(TAG, if (found) "SHOWN $resourceName($label)" else "MISS $resourceName($label)")
    return found
}

/** 取首个落在 [maxLeftPx] 左侧的候选节点：同名文案在左右两栏各有一份时用于区分。 */
private fun MacrobenchmarkScope.findFirst(selector: BySelector, maxLeftPx: Int): UiObject2? {
    val nodes = device.wait(Until.findObjects(selector), UI_TIMEOUT_MS) ?: return null
    return nodes.firstOrNull { node ->
        runCatching { node.visibleBounds.left <= maxLeftPx }.getOrDefault(false)
    }
}

/**
 * 校验节点可见且可交互后再点击：遮挡、移出屏幕或置灰的节点不响应点击，命中它们只会空耗一步。
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
        when {
            bounds.width() <= 0 || bounds.height() <= 0 -> {
                Log.i(TAG, "INVISIBLE $hint $bounds")
                settle()
                false
            }
            // 标题栏收起期间控件仅置灰不可点，按失败处理交由调用方重新唤出
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

/** 横向翻页到指定索引，按当前位置判断方向。 */
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

/**
 * 点按竖屏播放器页的艺术家信息行，进入该歌手的曲目列表。
 *
 * 艺术家文案随曲目动态变化，无法按固定资源匹配，故以底部控制栏的播放列表按钮为锚点，
 * 按固定距离反推该行位置（该行位于控制栏上方的信息块内）。
 * 点后以歌手页的标签文案确认已进入目标页，未命中只记日志，不影响后续流程。
 */
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

/** 点击横屏播放器左侧封面视觉区中心，进入 3D 封面轮播。 */
private fun MacrobenchmarkScope.tapCoverForCarousel() {
    device.click((device.displayWidth * COVER_CENTER_X).toInt(), (device.displayHeight * 0.5f).toInt())
    settle()
}

/**
 * 点击歌单分组列表的首行，进入该分组的曲目页。
 *
 * 分组名取自曲库、无法按固定文案匹配，故按坐标点击：首行位于页面标题栏下方的固定高度处。
 * 点后以曲目页的「播放全部」确认已下钻，未命中只记日志，不影响后续流程。
 */
private fun MacrobenchmarkScope.tapFirstGroupRow(expanded: Boolean) {
    val width = device.displayWidth
    val centerXDp = if (expanded) {
        // 宽屏右栏：左栏导航占固定宽度，右栏内容区水平居中
        PLAYLIST_RAIL_WIDTH_DP + (width / density() - PLAYLIST_RAIL_WIDTH_DP) / 2f
    } else {
        width / density() / 2f
    }
    // 窄屏内容列从沉浸标题栏下方开始，宽屏无标题栏
    val contentTopPx = if (expanded) 0 else statusBarHeightPx() + dpPx(TOP_APP_BAR_HEIGHT_DP)
    val y = contentTopPx + dpPx(GROUP_ROW_CENTER_DP)
    Log.i(TAG, "TAP group row first(expanded=$expanded) x=${dpPx(centerXDp)} y=$y")
    device.click(dpPx(centerXDp), y)
    settle()
    awaitText("playlist_play_all")
}

/**
 * 歌手页标签分页：左滑切到「专辑」标签。
 *
 * 手势落在标签分页的曲目列表内（头图与标签栏之下）；「歌曲」是首个标签，
 * 左滑才有可切换的目标，右滑在该标签上由页面用于回退一级。
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

/** 屏幕密度：坐标常量按 dp 定义，落到点击时按实测密度换算。 */
private fun density(): Float = appContext().resources.displayMetrics.density

/** 界面按状态栏高度为沉浸标题栏预留顶部空间，坐标估算须取同一基准。 */
private fun statusBarHeightPx(): Int {
    val resources = appContext().resources
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

private fun dpPx(dp: Float): Int = (dp * density()).toInt()

/** 宽屏左栏宽度像素：用于把按文案的点击限定在左栏导航内。 */
private fun railWidthPx(): Int = dpPx(PLAYLIST_RAIL_WIDTH_DP)

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

// 标题栏显隐类操作的成组重试次数：一次落空即重新唤出再试
private const val RETRY_TIMES = 3
// 朝向落定的轮询间隔
private const val ROTATION_POLL_MS = 100L