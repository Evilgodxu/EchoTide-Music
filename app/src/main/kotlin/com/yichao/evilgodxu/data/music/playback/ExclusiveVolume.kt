package com.yichao.evilgodxu.data.music.playback

import kotlin.math.pow

/** 独占音量：160 级、每级 0.5dB */
internal const val EXCLUSIVE_VOLUME_STEPS = 160

/** 每级衰减，单位 dB */
private const val STEP_DB = 0.5f

/** 默认档位 -30dB，之后按上次取值恢复 */
internal const val EXCLUSIVE_VOLUME_DEFAULT_STEP = 100

/**
 * 应用自持音量：160 级 0dB，0 级 -80dB
 * 与系统音量无关，音量键经会话远端通道落到这里。
 * 档位是唯一事实；跨线程读写，故 volatile。
 */
internal class ExclusiveVolume(initialStep: Int = EXCLUSIVE_VOLUME_DEFAULT_STEP) {

    @Volatile
    private var step = initialStep.coerceIn(0, EXCLUSIVE_VOLUME_STEPS)

    // 静音是会话临时状态，不落盘
    @Volatile
    private var muted = false

    val currentStep: Int get() = step

    val isMuted: Boolean get() = muted

    /** 当前应施加的线性增益，取值 0..1；写出侧每块缓冲读一次 */
    fun gain(): Float = if (muted) 0f else stepToGain(step)

    /** 写入档位，返回是否变了 */
    fun setStep(value: Int): Boolean {
        val next = value.coerceIn(0, EXCLUSIVE_VOLUME_STEPS)
        if (next == step) return false
        step = next
        return true
    }

    fun setMuted(value: Boolean): Boolean {
        if (value == muted) return false
        muted = value
        return true
    }
}

/** 档位转线性增益，满幅为 0dB */
internal fun stepToGain(step: Int): Float {
    val clamped = step.coerceIn(0, EXCLUSIVE_VOLUME_STEPS)
    return 10f.pow((clamped - EXCLUSIVE_VOLUME_STEPS) * STEP_DB / 20f)
}
