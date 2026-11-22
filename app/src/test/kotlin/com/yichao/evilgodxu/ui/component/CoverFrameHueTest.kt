package com.yichao.evilgodxu.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 色相锚定的夹取规则：偏差在容差内一律不动，超出才夹到边界，且色相按环形量走最短方向。
 * 这里只覆盖纯计算——位图上的逐像素应用由渲染路径负责，不在单测范围内。
 */
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
        // 350° 与 10° 相距 20°，不该被判成 340° 而拉回另一侧
        assertEquals(10f, anchorFrameHue(hue = 10f, anchorHue = 350f), DELTA)
        // 锚点 350°、色相 90°：最短方向是 +100°，夹到 +30° 即 20°
        assertEquals(20f, anchorFrameHue(hue = 90f, anchorHue = 350f), DELTA)
        // 锚点 350°、色相 280°：最短方向是 -70°，夹到 -30° 即 320°
        assertEquals(320f, anchorFrameHue(hue = 280f, anchorHue = 350f), DELTA)
    }

    private companion object {
        const val DELTA = 0.001f
    }
}
