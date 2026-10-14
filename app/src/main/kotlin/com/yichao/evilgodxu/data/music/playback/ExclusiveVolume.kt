package com.yichao.evilgodxu.data.music.playback

import kotlin.math.pow

/**
 * 独占路径音量的级数与每级衰减。
 *
 * 每级 0.5 dB、共 160 级，量程为满幅 0 dB 至 -80 dB。下界取到 -80 dB 为止：写出侧的音量是 15 位定点
 * 增益，-96 dB 以下一律乘成 0，再往下加级只是同一个静音的重复档，不如把级数留给听得见的区间。
 */
internal const val EXCLUSIVE_VOLUME_STEPS = 160

/** 每级衰减，单位 dB */
private const val STEP_DB = 0.5f

/** 首次使用的档位：-30 dB。起播不至于一上来就是满幅，之后一律按上次的取值恢复 */
internal const val EXCLUSIVE_VOLUME_DEFAULT_STEP = 100

/**
 * 独占期间的应用自持音量。
 *
 * 独占绕开混音器，系统媒体音量不再作用于这条流，响度必须由应用自己持有——故这里是一份**应用侧的档位**，
 * 与系统音量无关：档位越大越响，[EXCLUSIVE_VOLUME_STEPS] 级是满幅 0 dB，0 级是 -80 dB。音量键经媒体
 * 会话的远端音量通道落到这里（见 [ExclusiveVolumePlayer]），系统媒体音量一个数都不动。
 *
 * 只持有档位而不缓存线性增益：档位是唯一事实，增益每次由它换算得出，读盘写盘都只面对一个整数。
 * 档位由播放线程逐块读取（[gain]），改动来自主线程（音量键与启动时读盘），故按易变发布。
 */
internal class ExclusiveVolume(initialStep: Int = EXCLUSIVE_VOLUME_DEFAULT_STEP) {

    @Volatile
    private var step = initialStep.coerceIn(0, EXCLUSIVE_VOLUME_STEPS)

    /** 静音是会话侧的临时状态，不落盘：把"上次停在静音"记到下次启动，表现为"一开机就像坏了" */
    @Volatile
    private var muted = false

    val currentStep: Int get() = step

    val isMuted: Boolean get() = muted

    /** 当前应施加的线性增益，取值 0..1；写出侧每块缓冲读一次 */
    fun gain(): Float = if (muted) 0f else stepToGain(step)

    /** 写入档位，返回是否真的变了：未变则调用方无须落盘，也无须让会话重新播报 */
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

/**
 * 档位到线性增益。
 *
 * 满幅（[EXCLUSIVE_VOLUME_STEPS] 级）为 0 dB，每降一级衰减 0.5 dB。越界一律按两端取值，
 * 调用方无须先钳。
 */
internal fun stepToGain(step: Int): Float {
    val clamped = step.coerceIn(0, EXCLUSIVE_VOLUME_STEPS)
    return 10f.pow((clamped - EXCLUSIVE_VOLUME_STEPS) * STEP_DB / 20f)
}
