package com.yichao.evilgodxu.data.music.playback

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直出免打扰的进出状态机。
 *
 * 两条关键约束：一是「谁发起的改动由谁还原」——通知已被静音时不得接管，否则退出会改掉用户自己的设置；
 * 二是档位不得落到完全静音——平台会连 STREAM_MUSIC 一并静音，音乐会被自己的免打扰压掉。
 * 用例围绕这两条构造，而不是只验证写入值。
 */
class DirectOutputDoNotDisturbTest {

    private val all = NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS
    private val none = NotificationManager.INTERRUPTION_FILTER_NONE

    private var filter = all
    private var granted = true
    private val writes = mutableListOf<Int>()

    private fun controller(): DirectOutputDoNotDisturb = DirectOutputDoNotDisturb(
        isAccessGranted = { granted },
        readFilter = { filter },
        writeFilter = {
            writes += it
            filter = it
        },
    )

    @Test
    fun directOutputMutesNotificationsThenRestoresPreviousFilter() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.BIT_PERFECT)
        assertEquals(alarms, filter)

        controller.onModeChanged(AudioOutputMode.MIXER)
        assertEquals(all, filter)
        assertEquals(listOf(alarms, all), writes)
    }

    @Test
    fun formatLockedAlsoCountsAsDirectOutput() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)

        assertEquals(alarms, filter)
    }

    // 完全静音会把 STREAM_MUSIC 一并压掉，故任何路径都不得写入该档位
    @Test
    fun totalSilenceIsNeverWritten() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.BIT_PERFECT)
        controller.onModeChanged(AudioOutputMode.MIXER)
        controller.release()

        assertFalse(writes.contains(none))
    }

    // 进入前通知已被静音：目标状态已满足，不接管也不在退出时还原
    @Test
    fun alreadySilencedSystemIsLeftUntouched() {
        listOf(none, alarms).forEach { silenced ->
            filter = silenced
            writes.clear()
            val controller = controller()

            controller.onModeChanged(AudioOutputMode.BIT_PERFECT)
            controller.onModeChanged(AudioOutputMode.MIXER)

            assertTrue("档位 $silenced 不应被改写", writes.isEmpty())
            assertEquals(silenced, filter)
        }
    }

    // 原档位是「仅限优先项」时按原值还原，而不是一律回到允许全部打扰
    @Test
    fun priorityFilterIsRestoredVerbatim() {
        filter = priority
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.BIT_PERFECT)
        controller.onModeChanged(AudioOutputMode.MIXER)

        assertEquals(priority, filter)
    }

    @Test
    fun withoutPolicyAccessNothingIsWritten() {
        granted = false
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.BIT_PERFECT)

        assertTrue(writes.isEmpty())
        assertEquals(all, filter)
    }

    // 成色未成立时不产生任何写入，避免未直出时也动系统档位
    @Test
    fun mixerModeWithoutHoldingWritesNothing() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.MIXER)

        assertTrue(writes.isEmpty())
    }

    // 服务销毁兜底：持有时无条件还原，未持有时不动
    @Test
    fun releaseRestoresOnlyWhenHolding() {
        val controller = controller()
        controller.onModeChanged(AudioOutputMode.BIT_PERFECT)

        controller.release()
        assertEquals(all, filter)

        val untouched = controller()
        untouched.release()
        // 首次接管的进入与还原共两次写入，未持有的 release 不追加
        assertEquals(2, writes.size)
    }
}
