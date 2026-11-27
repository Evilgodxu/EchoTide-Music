package com.yichao.evilgodxu.data.music.playback

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 谁改谁还原；不得落到完全静音 */
class DirectOutputDoNotDisturbTest {

    private val all = NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS
    private val none = NotificationManager.INTERRUPTION_FILTER_NONE

    private var filter = all
    private var granted = true
    private val writes = mutableListOf<Int>()

    private fun controller(sdkInt: Int = 0): DirectOutputDoNotDisturb = DirectOutputDoNotDisturb(
        isAccessGranted = { granted },
        readFilter = { filter },
        writeFilter = {
            writes += it
            filter = it
        },
        sdkInt = sdkInt,
    )

    @Test
    fun `直出期间静音通知，退出后恢复原过滤规则`() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)
        assertEquals(alarms, filter)

        controller.onModeChanged(AudioOutputMode.MIXER)
        assertEquals(all, filter)
        assertEquals(listOf(alarms, all), writes)
    }

    @Test
    fun `格式锁定同样算作直出`() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)

        assertEquals(alarms, filter)
    }

    // 完全静音会压掉 STREAM_MUSIC
    @Test
    fun `从不写入完全静音档`() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)
        controller.onModeChanged(AudioOutputMode.MIXER)
        controller.release()

        assertFalse(writes.contains(none))
    }

    // 已静音则不接管、不还原
    @Test
    fun `系统已静音时不作改动`() {
        listOf(none, alarms).forEach { silenced ->
            filter = silenced
            writes.clear()
            val controller = controller()

            controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)
            controller.onModeChanged(AudioOutputMode.MIXER)

            assertTrue("档位 $silenced 不应被改写", writes.isEmpty())
            assertEquals(silenced, filter)
        }
    }

    // 按原档位还原，不回到全部打扰
    @Test
    fun `优先级过滤规则原样恢复`() {
        filter = priority
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)
        controller.onModeChanged(AudioOutputMode.MIXER)

        assertEquals(priority, filter)
    }

    @Test
    fun `无免打扰访问权时不写入`() {
        granted = false
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)

        assertTrue(writes.isEmpty())
        assertEquals(all, filter)
    }

    // 未直出不写系统档位
    @Test
    fun `未持有时混音模式不写入`() {
        val controller = controller()

        controller.onModeChanged(AudioOutputMode.MIXER)

        assertTrue(writes.isEmpty())
    }

    // 15+ 须以 UNKNOWN 停用自身规则
    @Test
    fun `新平台在退出时停用自己的规则`() {
        filter = priority
        val controller = controller(sdkInt = 35)

        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)
        assertEquals(alarms, filter)

        controller.release()
        assertEquals(
            "退出应停用应用自己的规则，而不是把全局档位写成自己的规则",
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            filter,
        )
        assertEquals(listOf(alarms, NotificationManager.INTERRUPTION_FILTER_UNKNOWN), writes)
    }

    // 持有时还原，未持有不动
    @Test
    fun `仅在持有时才恢复`() {
        val controller = controller()
        controller.onModeChanged(AudioOutputMode.FORMAT_LOCKED)

        controller.release()
        assertEquals(all, filter)

        val untouched = controller()
        untouched.release()
        // 接管两次写入，未持有不追加
        assertEquals(2, writes.size)
    }
}
