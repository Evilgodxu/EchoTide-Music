package com.yichao.evilgodxu.data.music.playback.usb

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.Closeable
import kotlin.math.pow

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
 * 取的是**平台的音量曲线**而不是按档位线性摊分：系统的媒体音量曲线在低档位压得很深（首档常达数十 dB），
 * 线性摊分会让最低一档也相当响，与系统自身的行为对不上。
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
        val next = readGain()
        if (next != currentGain) {
            CrashLogManager.logInfo(
                LOG_TAG,
                "系统媒体音量 → 软件音量：${(next * 100).toInt()}%（独占路径绕开混音器，系统音量须自己施加）",
            )
        }
        currentGain = next
    }

    private fun readGain(): Float {
        val manager = audioManager ?: return 1f
        val index = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: return 1f
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: 0
        if (max <= 0) return 1f
        val clamped = index.coerceIn(0, max)
        // 按 USB 设备类别取曲线：实际发声的就是这台解码器，拿别的类别的曲线会与听感对不上。
        // 该查询只按类别查表，与设备当前是否在系统里无关——独占期间它正是被本应用拿走了，
        // 而这里要的恰恰是"若由系统来放，它会衰减多少"
        val db = runCatching {
            manager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, clamped, AudioDeviceInfo.TYPE_USB_DEVICE)
        }.getOrNull()
        if (db != null) return dbToLinearGain(db)
        return clamped.toFloat() / max
    }
}

/**
 * 分贝到线性增益。
 *
 * 取平台曲线而不是按档位线性摊分，原因见 [SystemMediaVolume]；这里把曲线给出的衰减换算成幅度乘数。
 * 静音档在平台上就是负无穷，对应增益 0；放大（正分贝）一律按满幅处理——独占链路上放大没有意义，
 * 越过整型边界听到的会是削顶失真。
 */
internal fun dbToLinearGain(db: Float): Float {
    if (db.isNaN()) return 1f
    if (db == Float.NEGATIVE_INFINITY) return 0f
    return 10f.pow(db / 20f).coerceIn(0f, 1f)
}
