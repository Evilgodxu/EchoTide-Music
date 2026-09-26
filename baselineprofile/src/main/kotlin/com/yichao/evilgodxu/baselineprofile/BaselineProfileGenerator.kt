package com.yichao.evilgodxu.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.yichao.evilgodxu"

/**
 * 生成基准配置与启动配置。
 *
 * `includeInStartupProfile = true` 的规则会额外产出 Startup Profile，
 * 供 R8 在编译期把启动路径的类集中到首个 DEX，减少冷启动缺页。
 * 因此启动相关的用户历程必须放在本类，其余历程应另建测试类。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        // 从启动器冷启动并等待首帧，覆盖 Application 初始化到首页首次组合
        startActivityAndWait()
        // 首页可见前加载本地曲库与封面，这段路径同样属于启动体验
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 5_000)
    }
}
