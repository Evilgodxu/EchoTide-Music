package com.yichao.evilgodxu.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.yichao.evilgodxu"
private const val TAG = "FrameTimingBench"

// 首帧等待上限，冷启动慢时兜底
private const val HOME_TIMEOUT_MS = 30_000L
// 单个控件出现等待上限
private const val UI_TIMEOUT_MS = 5_000L
// 动作间隔，等转场落定
private const val SETTLE_MS = 700L

// 静置采样窗口：覆盖层在场时底层若仍在推帧，会持续落在这段窗口里
private const val IDLE_SAMPLE_MS = 4_000L

// 迭代次数：帧指标波动大，取 5 次中位数更稳
private const val ITERATIONS = 5

/**
 * 帧指标基准：首页静止、覆盖层往返、面板开合三条路径。
 * 用于对比“被遮挡的页面是否停止推帧”改动前后的帧耗时分布。
 * 单位与口径由 FrameTimingMetric 给出，跨设备不可直接比，只与自身基线比。
 */
@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** 首页静止：背景帧循环的基线 */
    @Test
    fun homeIdle() = measure {
        SystemClock.sleep(IDLE_SAMPLE_MS)
    }

    /** 设置页往返：覆盖层在场时首页应整体停摆 */
    @Test
    fun overlayRoundTrip() = measure {
        tap("settings_title")
        awaitText("settings_title")
        SystemClock.sleep(IDLE_SAMPLE_MS)
        device.pressBack()
        settle()
    }

    /** 播放列表面板开合：面板压住播放页时仍应只算面板本身的开销 */
    @Test
    fun queuePanelRoundTrip() = measure {
        tap("music_panel_playlist")
        awaitDesc("music_panel_sort")
        SystemClock.sleep(IDLE_SAMPLE_MS)
        device.pressBack()
        settle()
    }

    private fun measure(body: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        // 关掉 AOT 编译优化，避免编译差异盖住运行时改动
        compilationMode = CompilationMode.None(),
        iterations = ITERATIONS,
        setupBlock = { prepare() },
    ) {
        startActivityAndWait()
        awaitHome()
        body()
    }
}

/** 授予所需权限并清后台；缺任一权限会弹窗挡住历程 */
private fun MacrobenchmarkScope.prepare() {
    listOf(
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BLUETOOTH_CONNECT",
    ).forEach { device.executeShellCommand("pm grant $PACKAGE $it") }
    device.executeShellCommand("appops set $PACKAGE MANAGE_EXTERNAL_STORAGE allow")
    device.executeShellCommand("appops set $PACKAGE SYSTEM_ALERT_WINDOW allow")
    device.executeShellCommand("am force-stop $PACKAGE")
}

private fun MacrobenchmarkScope.awaitHome() {
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), HOME_TIMEOUT_MS)
    settle()
}

/** 按无障碍标签点击，失败只记日志 */
private fun MacrobenchmarkScope.tap(resourceName: String): Boolean {
    val label = label(resourceName)
    val node = device.wait(Until.findObject(By.desc(label)), UI_TIMEOUT_MS)
    if (node == null) {
        Log.i(TAG, "MISS desc $resourceName($label)")
        settle()
        return false
    }
    return runCatching {
        node.click()
        settle()
        true
    }.getOrElse {
        Log.i(TAG, "FAIL tap $resourceName($label)", it)
        settle()
        false
    }
}

/** 等文案出现确认页面已切换 */
private fun MacrobenchmarkScope.awaitText(resourceName: String): Boolean {
    val label = label(resourceName)
    val found = device.wait(Until.hasObject(By.text(label)), UI_TIMEOUT_MS)
    Log.i(TAG, if (found) "SHOWN $resourceName($label)" else "MISS $resourceName($label)")
    return found
}

/** 等标签出现确认展开态 */
private fun MacrobenchmarkScope.awaitDesc(resourceName: String): Boolean {
    val label = label(resourceName)
    val found = device.wait(Until.hasObject(By.desc(label)), UI_TIMEOUT_MS)
    Log.i(TAG, if (found) "SHOWN $resourceName($label)" else "MISS $resourceName($label)")
    return found
}

/** 从被测包上下文解析文案，插桩上下文常指向测试包 */
private fun label(resourceName: String): String {
    val context = runCatching {
        InstrumentationRegistry.getInstrumentation().targetContext
            .createPackageContext(PACKAGE, 0)
    }.getOrElse {
        InstrumentationRegistry.getInstrumentation().targetContext
    }
    val id = context.resources.getIdentifier(resourceName, "string", PACKAGE)
    if (id == 0) return resourceName
    return runCatching { context.getString(id) }.getOrDefault(resourceName)
}

private fun settle() {
    SystemClock.sleep(SETTLE_MS)
}
