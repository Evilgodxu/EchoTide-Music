package com.yichao.evilgodxu.data.music.playback.usb

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.Closeable
import kotlin.math.roundToInt

// 类名前缀：音量的订阅与重读都记在此名下
private const val LOG_TAG = "SystemMediaVolume"

/**
 * 兜底重读的最小间隔。
 *
 * 观察者是首选路径（音量键一按即响应），但个别机型对系统设置的写入不发通知，只靠观察者会让音量
 * 永久停在起播那一刻的取值上，故按此间隔再主动读一次。
 */
private const val REREAD_INTERVAL_MS = 500L

/**
 * 系统媒体音量。
 *
 * 这条路径存在的理由：本应用的音量由**系统**掌管（播放器音量恒为 1，见 MusicPlaybackState），而独占
 * 绕开了混音器——`AudioManager` 的音量不再作用于这条流。于是系统音量滑杆与音量键会变成"按了没反应"，
 * 而链路停在满幅：听感上就是"太响且调不动"。唯一的解是自己施加，把系统音量取出来当软件音量。
 *
 * 换算走档位百分比：先把当前档位折算成 0..100 的整数百分比，再归一化成 0.0..1.0 的软件音量。
 * 不经 `getStreamVolumeDb` 的平台曲线——那条曲线按 USB 设备类别查表，且**因机而异**：多数机型把中低
 * 档位压到数十 dB 以下，换算成幅度后整条链路近乎无声，同一档位在不同手机上响度天差地别。档位百分比
 * 与机型无关，是这里唯一说得清的口径。
 */
internal class SystemMediaVolume(
    private val context: Context,
    private val audioManager: AudioManager?,
) : Closeable {

    @Volatile
    private var currentGain = 1f

    private var lastReadMs = 0L
    private var observing = false

    /** 系统设置的任何变更都会回调（含各路音量键），故读一次即可分辨；单次读取只是两次属性查询 */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            reread()
        }
    }

    fun start() {
        if (observing) return
        observing = true
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                /* notifyForDescendants= */ true,
                observer,
            )
        }.onFailure {
            CrashLogManager.logException(LOG_TAG, "订阅系统音量失败，改为按间隔重读", it)
        }
        reread()
    }

    fun stop() {
        if (!observing) return
        observing = false
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
            .onFailure { CrashLogManager.logException(LOG_TAG, "退订系统音量失败", it) }
    }

    override fun close() = stop()

    /**
     * 当前系统媒体音量的线性增益，取值 0..1。
     *
     * 每块缓冲都会问一次，故按固定间隔才真去重读：读取是两次跨进程查询，逐块都读白耗播放线程，
     * 而 500ms 的兜底间隔足以让音量键的响应看起来是即时的（观察者正常情况下更早一步生效）。
     */
    fun gain(): Float {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadMs >= REREAD_INTERVAL_MS) reread()
        return currentGain
    }

    private fun reread() {
        lastReadMs = SystemClock.elapsedRealtime()
        currentGain = readGain()
    }

    private fun readGain(): Float {
        val manager = audioManager ?: return 1f
        val index = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: return 1f
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: 0
        return systemVolumeGain(index, max)
    }
}

/**
 * 当前档位占最大档位的百分比，取整到 0..100。
 *
 * 归一化以这个整数百分比为基准（见 [systemVolumeGain]），于是用户看到的"音量百分之几"与实际施加的
 * 幅度是同一个数，中间不再夹任何非线性换算。
 *
 * [max] 非正表示读不到最大档位：此时没有可比的基准，按满幅处理——压低音量只会把用户挡在声音之外。
 */
internal fun volumePercent(index: Int, max: Int): Int {
    if (max <= 0) return 100
    return (index.coerceIn(0, max) * 100f / max).roundToInt()
}

/** 档位到软件音量的整条换算：档位 → 0..100 整数百分比 → 归一化到 0.0f..1.0f */
internal fun systemVolumeGain(index: Int, max: Int): Float = volumePercent(index, max) / 100f
