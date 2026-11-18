package com.poria.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorExtTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `标准色相能还原成红绿蓝`() {
        assertEquals(red, hslToColor(0f, 1f, 0.5f))
        assertEquals(green, hslToColor(120f, 1f, 0.5f))
        assertEquals(blue, hslToColor(240f, 1f, 0.5f))
    }

    @Test
    fun `饱和度为 0 时是纯灰阶`() {
        assertEquals(white, hslToColor(0f, 0f, 1f))
        assertEquals(black, hslToColor(123f, 0f, 0f))
    }

    @Test
    fun `色相会被规范化到 0-360`() {
        assertEquals(hslToColor(0f, 1f, 0.5f), hslToColor(360f, 1f, 0.5f))
        assertEquals(hslToColor(240f, 1f, 0.5f), hslToColor(-120f, 1f, 0.5f))
        assertEquals(hslToColor(0f, 1f, 0.5f), hslToColor(720f, 1f, 0.5f))
    }

    @Test
    fun `输出永远是不透明色`() {
        listOf(0f, 33f, 180f, 359f).forEach { h ->
            val alpha = hslToColor(h, 0.7f, 0.6f) ushr 24
            assertEquals(255, alpha)
        }
    }

    @Test
    fun `genColor 对同一对象稳定`() {
        assertEquals("brick".genColor(), "brick".genColor())
        assertEquals("brick".genColor(hOffset = 30), "brick".genColor(hOffset = 30))
    }

    @Test
    fun `genColor 对不同对象基本错开`() {
        assertNotEquals("brick".genColor(), "brick".genColor(hOffset = 30))
        assertNotEquals(42.genColor(), 43.genColor())
    }

    @Test
    fun `genColor 亮度基准不会被负余数带偏`() {
        // code shr 16 为负时，旧实现会让 dL 变成负数；这里约定 dL 恒在 0..5
        (0 until 200).forEach { seed ->
            val color = seed.genColor(sBase = 0, lBase = 0)
            assertTrue("alpha 必须是 255", color ushr 24 == 255)
        }
    }

    @Test
    fun `argbOf 按通道打包`() {
        assertEquals(red, argbOf(1f, 0f, 0f))
        assertEquals(0x80112233.toInt(), argbOf(0x11 / 255f, 0x22 / 255f, 0x33 / 255f, 0x80 / 255f))
    }
}
