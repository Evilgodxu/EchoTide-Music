package com.yichao.evilgodxu.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/** 色相在容差内不动，超出夹到边界 */
class CoverFrameHueTest {

    @Test
    fun `偏差在容差内时色相原样返回`() {
        assertEquals(14f, anchorFrameHue(hue = 14f, anchorHue = 0f), DELTA)
        assertEquals(348f, anchorFrameHue(hue = 348f, anchorHue = 350f), DELTA)
    }

    @Test
    fun `偏差恰好等于容差时视为容差内`() {
        assertEquals(30f, anchorFrameHue(hue = 30f, anchorHue = 0f), DELTA)
        assertEquals(330f, anchorFrameHue(hue = 330f, anchorHue = 0f), DELTA)
    }

    @Test
    fun `偏差超出容差时夹到边界而非整体替换`() {
        assertEquals(30f, anchorFrameHue(hue = 100f, anchorHue = 0f), DELTA)
        assertEquals(330f, anchorFrameHue(hue = 280f, anchorHue = 0f), DELTA)
    }

    @Test
    fun `跨零度时按最短方向夹取`() {
        // 相差 20°，按最短方向取
        assertEquals(10f, anchorFrameHue(hue = 10f, anchorHue = 350f), DELTA)
        // +100° 夹到 +30° 即 20°
        assertEquals(20f, anchorFrameHue(hue = 90f, anchorHue = 350f), DELTA)
        // -70° 夹到 -30° 即 320°
        assertEquals(320f, anchorFrameHue(hue = 280f, anchorHue = 350f), DELTA)
    }

    private companion object {
        const val DELTA = 0.001f
    }
}
