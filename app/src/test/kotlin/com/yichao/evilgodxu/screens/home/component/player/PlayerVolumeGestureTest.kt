package com.yichao.evilgodxu.screens.home.component.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放按钮左右滑动音量手势的档位换算。
 *
 * 关注两件事：方向（左负右正、按锚点原路可回），以及换到不同上限时的取整与边界钳制。
 */
class PlayerVolumeGestureTest {

    @Test
    fun dragRightRaisesAndDragLeftLowers() {
        assertEquals(50, volumeLevelForDrag(0.5f, 0f, 200f, 100))
        assertEquals(75, volumeLevelForDrag(0.5f, 50f, 200f, 100))
        assertEquals(25, volumeLevelForDrag(0.5f, -50f, 200f, 100))
    }

    @Test
    fun dragBeyondRangeClampsToBounds() {
        assertEquals(100, volumeLevelForDrag(0.5f, 10_000f, 200f, 100))
        assertEquals(1, volumeLevelForDrag(0.5f, -10_000f, 200f, 100))
    }

    @Test
    fun minimumIsOnePercentOfRange() {
        // 1% 量程向上取整：100 档最低 1、160 档最低 2、15 档最低 1
        assertEquals(1, minVolumeLevel(100))
        assertEquals(2, minVolumeLevel(160))
        assertEquals(1, minVolumeLevel(15))
        assertEquals(0, minVolumeLevel(0))
    }

    @Test
    fun zeroStartStillLiftsToMinimum() {
        assertEquals(1, volumeLevelForDrag(0f, 0f, 160f, 100))
        assertEquals(2, volumeLevelForDrag(0f, 0f, 160f, 160))
    }

    @Test
    fun levelRoundsToNearestStep() {
        // 15 档的系统媒体音量：半程处 7.5 取到 8
        assertEquals(8, volumeLevelForDrag(0.5f, 0f, 160f, 15))
        assertEquals(15, volumeLevelForDrag(0.5f, 80f, 160f, 15))
    }

    @Test
    fun levelStaysAnchoredWhenDraggingBackToStart() {
        // 换算始终以起手档位为锚点：拖出去再回到起点应回到同一个档位，不随逐次取整漂移
        val start = 0.42f
        val anchorLevel = volumeLevelForDrag(start, 0f, 160f, 160)
        assertEquals(67, anchorLevel)
        assertEquals(107, volumeLevelForDrag(start, 40f, 160f, 160))
        assertEquals(anchorLevel, volumeLevelForDrag(start, 0f, 160f, 160))
    }

    @Test
    fun degenerateRangesReturnZero() {
        assertEquals(0, volumeLevelForDrag(0.5f, 10f, 200f, 0))
        assertEquals(0, volumeLevelForDrag(0.5f, 10f, 0f, 100))
    }
}
