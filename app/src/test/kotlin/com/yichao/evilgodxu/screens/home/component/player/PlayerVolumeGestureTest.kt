package com.yichao.evilgodxu.screens.home.component.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 音量手势档位：左负右正，按锚点可回 */
class PlayerVolumeGestureTest {

    @Test
    fun `右拖升、左拖降`() {
        assertEquals(50, volumeLevelForDrag(0.5f, 0f, 200f, 100))
        assertEquals(75, volumeLevelForDrag(0.5f, 50f, 200f, 100))
        assertEquals(25, volumeLevelForDrag(0.5f, -50f, 200f, 100))
    }

    @Test
    fun `拖动越界钳到边界`() {
        assertEquals(100, volumeLevelForDrag(0.5f, 10_000f, 200f, 100))
        assertEquals(1, volumeLevelForDrag(0.5f, -10_000f, 200f, 100))
    }

    @Test
    fun `最小值为量程的百分之一`() {
        // 1% 量程向上取整
        assertEquals(1, minVolumeLevel(100))
        assertEquals(2, minVolumeLevel(160))
        assertEquals(1, minVolumeLevel(15))
        assertEquals(0, minVolumeLevel(0))
    }

    @Test
    fun `从零起拖仍抬到最小值`() {
        assertEquals(1, volumeLevelForDrag(0f, 0f, 160f, 100))
        assertEquals(2, volumeLevelForDrag(0f, 0f, 160f, 160))
    }

    @Test
    fun `档位取最近的整档`() {
        // 15 档半程 7.5 取 8
        assertEquals(8, volumeLevelForDrag(0.5f, 0f, 160f, 15))
        assertEquals(15, volumeLevelForDrag(0.5f, 80f, 160f, 15))
    }

    @Test
    fun `拖回起点时档位保持锚定`() {
        // 以起手档位为锚点，回拖不漂移
        val start = 0.42f
        val anchorLevel = volumeLevelForDrag(start, 0f, 160f, 160)
        assertEquals(67, anchorLevel)
        assertEquals(107, volumeLevelForDrag(start, 40f, 160f, 160))
        assertEquals(anchorLevel, volumeLevelForDrag(start, 0f, 160f, 160))
    }

    @Test
    fun `退化量程返回零`() {
        assertEquals(0, volumeLevelForDrag(0.5f, 10f, 200f, 0))
        assertEquals(0, volumeLevelForDrag(0.5f, 10f, 0f, 100))
    }
}
