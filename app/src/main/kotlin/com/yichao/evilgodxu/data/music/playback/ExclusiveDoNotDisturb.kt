package com.yichao.evilgodxu.data.music.playback

import android.app.NotificationManager
import com.yichao.evilgodxu.log.CrashLogManager

// 诊断日志的类名前缀：与独占输出同处一条链路，免打扰的进出决策都记在此名下
private const val LOG_TAG = "ExclusiveDoNotDisturb"

/**
 * USB 独占聆听期间的系统免打扰。
 *
 * 位完美与格式独占一旦成立，播放已挂上专用输出流，此时通知与系统提示音是最直接的打扰源。
 * 本类把独占成色映射为系统免打扰的进入与退出：成色成立即置为「仅闹钟」，成色消失（拔线、切换输出、
 * 关闭独占开关、服务结束）即还原。退出只认成色、不认播放与暂停——暂停时跟着进出会让手机的静音状态
 * 反复翻转。
 *
 * 档位取「仅闹钟」而非「完全静音」：平台按档位静音整条流（AudioService.updateZenModeAffectedStreams），
 * 完全静音连 STREAM_MUSIC 一并压掉，音乐会被自己的免打扰静音，与「专注聆听」的初衷相反；
 * 仅闹钟只压 SYSTEM、NOTIFICATION、RING，通知与铃声不再响而媒体照常。更进一步的「连闹钟也静音但保留
 * 媒体」平台没有对应的公开档位，需自定义免打扰规则，本类不涉足。
 *
 * 只还原「由本类发起」的改动：进入前通知若已被静音（系统处于仅闹钟或完全静音），目标状态本就满足，
 * 此时不改动也不标记，退出时才不会替用户改掉他自己的设置。系统处于允许全部打扰或仅限优先项时正常接管，
 * 退出时按 [restoreFilter] 原样还原。
 *
 * 三个读写口经构造注入而非直接持有 [NotificationManager]：进出逻辑承载的是「何时还原、能否接手」这类
 * 判断，注入后可在 JVM 上直接验证，无需真机。系统免打扰的读写本身是 binder 调用、不限线程，但
 * [holding] 与 [restoreFilter] 是一对不可分的状态，调用方须保证 [onModeChanged] 与 [release] 同处一线程
 * （服务侧统一投递主线程）。
 */
class ExclusiveDoNotDisturb(
    /** 是否已获系统免打扰访问权；未获权时读写档位都会失败，故先问它 */
    private val isAccessGranted: () -> Boolean,
    /** 读取当前系统免打扰档位 */
    private val readFilter: () -> Int,
    /** 写入系统免打扰档位 */
    private val writeFilter: (Int) -> Unit,
) {

    /** 是否已由本类置为完全静音；为真才在退出时还原 */
    private var holding = false

    /** 接管前的系统档位，仅在 [holding] 为真时有意义 */
    private var restoreFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    /**
     * 独占成色变化时驱动免打扰进出。
     *
     * [AudioOutputMode.MIXER] 表示未独占（含设备被拔出、属性未被系统受理、独占开关关闭），
     * 其余成色都视为进入聆听。
     */
    fun onModeChanged(mode: AudioOutputMode) {
        if (mode == AudioOutputMode.MIXER) exitSilence() else enterSilence()
    }

    /** 无条件还原并解除接管；独占输出释放时兜底调用，避免服务销毁后手机停在静音 */
    fun release() {
        exitSilence()
    }

    private fun enterSilence() {
        if (holding) return
        if (!runCatching { isAccessGranted() }.getOrDefault(false)) return
        val current = currentFilter() ?: return
        // 通知已被静音（仅闹钟、完全静音）：目标状态本就满足，动它只会把用户自己的设置换成我们的
        if (current == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
            current == NotificationManager.INTERRUPTION_FILTER_NONE
        ) {
            return
        }
        if (!setFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)) return
        restoreFilter = current
        holding = true
        logInfo("独占聆听进入免打扰：置为仅闹钟，原档位 ${filterName(current)}")
    }

    private fun exitSilence() {
        if (!holding) return
        // 先落状态再写系统：写失败也视为已放弃接管，避免残留标记让下次还原写在错误的档位上
        val filter = restoreFilter
        holding = false
        if (setFilter(filter)) {
            logInfo("独占结束退出免打扰：还原为 ${filterName(filter)}")
        }
    }

    private fun currentFilter(): Int? = runCatching { readFilter() }
        .onFailure { CrashLogManager.logException(LOG_TAG, "读取系统免打扰档位失败", it) }
        .getOrNull()

    private fun setFilter(filter: Int): Boolean = runCatching { writeFilter(filter) }
        .onFailure {
            CrashLogManager.logException(LOG_TAG, "写入系统免打扰档位失败：${filterName(filter)}", it)
        }
        .isSuccess

    private fun logInfo(message: String) = CrashLogManager.logInfo(LOG_TAG, message)

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "允许全部打扰"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅限优先项"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "完全静音"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
        else -> "档位$filter"
    }
}
