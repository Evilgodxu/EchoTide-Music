package com.yichao.evilgodxu.data.music.playback

import android.app.NotificationManager
import android.os.Build
import com.yichao.evilgodxu.log.CrashLogManager

// 日志前缀，仅出错时记录
private const val LOG_TAG = "DirectOutputDoNotDisturb"

/**
 * USB 直出期间置「仅闹钟」，消失还原。
 * 只压非媒体流，只动本类发起的改动。
 * 模式回调与 release 须同线程。
 */
class DirectOutputDoNotDisturb(
    /** 是否有免打扰访问权 */
    private val isAccessGranted: () -> Boolean,
    /** 读取当前系统免打扰档位 */
    private val readFilter: () -> Int,
    /** 写入系统免打扰档位 */
    private val writeFilter: (Int) -> Unit,
    /** 系统版本，Android 15 起写自有规则 */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    /** Android 15+ 写的是应用自有隐式规则 */
    private val managesOwnRule = sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** 是否已置为完全静音，真才在退出时还原 */
    private var holding = false

    /** 接管前档位，仅 holding 时有效 */
    private var restoreFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    /** 成色变化驱动进出；MIXER 表示未直出 */
    fun onModeChanged(mode: AudioOutputMode) {
        if (mode == AudioOutputMode.MIXER) exitSilence() else enterSilence()
    }

    /** 无条件还原并解除接管 */
    fun release() {
        if (!managesOwnRule) {
            exitSilence()
            return
        }
        // Android 15+ 停用自有规则，同时清进程遗留
        holding = false
        setFilter(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
    }

    private fun enterSilence() {
        if (holding) return
        if (!runCatching { isAccessGranted() }.getOrDefault(false)) return
        val current = currentFilter() ?: return
        // 已被静音则不动用户设置
        if (current == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
            current == NotificationManager.INTERRUPTION_FILTER_NONE
        ) {
            return
        }
        if (!setFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)) return
        restoreFilter = current
        holding = true
    }

    private fun exitSilence() {
        if (!holding) return
        // 先落状态再写系统，写失败也视为已放弃
        val filter = restoreFilter
        holding = false
        // 15+ 写 UNKNOWN 停用规则；更早还原全局档位
        setFilter(if (managesOwnRule) NotificationManager.INTERRUPTION_FILTER_UNKNOWN else filter)
    }

    private fun currentFilter(): Int? = runCatching { readFilter() }
        .onFailure { CrashLogManager.logException(LOG_TAG, "读取系统免打扰档位失败", it) }
        .getOrNull()

    private fun setFilter(filter: Int): Boolean = runCatching { writeFilter(filter) }
        .onFailure {
            CrashLogManager.logException(LOG_TAG, "写入系统免打扰档位失败：${filterName(filter)}", it)
        }
        .isSuccess

    private fun filterName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "允许全部打扰"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅限优先项"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "完全静音"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
        else -> "档位$filter"
    }
}
