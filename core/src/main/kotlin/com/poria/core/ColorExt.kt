package com.poria.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * HSL → ARGB 颜色。参数范围：h ∈ [0, 360)，s ∈ [0, 1]，l ∈ [0, 1]。
 *
 * 纯 Kotlin 实现（不依赖 android.graphics.Color），因此能在 JVM 单测里直接验证。
 * 返回值按 Android 习惯打包成 `0xAARRGGBB`。
 */
fun hslToColor(h: Float, s: Float, l: Float): Int {
    val hue = normalizeHue(h)
    val sat = s.coerceIn(0f, 1f)
    val light = l.coerceIn(0f, 1f)

    val c = (1f - abs(2f * light - 1f)) * sat
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = light - c / 2f

    val (r, g, b) = when ((hue / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return argbOf(r + m, g + m, b + m)
}

/**
 * 由对象自身生成一个稳定的浅色主题色（同一个对象每次得到同一种颜色）。
 *
 * @param hOffset 色相偏移，用来让相邻的几个元素错开颜色
 * @param sBase   基础饱和度（0..100），实际值会在 `sBase..sBase+5` 之间浮动
 * @param lBase   基础亮度（0..100），实际值会在 `lBase..lBase+5` 之间浮动
 */
fun Any.genColor(hOffset: Int = 0, sBase: Int = 60, lBase: Int = 85): Int {
    val code = scrambleHash()
    val h = Math.floorMod((code and Int.MAX_VALUE) + hOffset, 360)
    val s = sBase + Math.floorMod(code and 0xFFFF, 6)
    // 注意用 floorMod：code shr 16 可能是负数，普通 % 会得到负值
    val l = lBase + Math.floorMod(code shr 16, 6)
    return hslToColor(
        h.toFloat().coerceIn(0f, 359f),
        s.coerceIn(0, 100) / 100f,
        l.coerceIn(0, 100) / 100f
    )
}

/** 把 r/g/b（0f..1f）打包成不透明 ARGB。 */
fun argbOf(r: Float, g: Float, b: Float, a: Float = 1f): Int =
    (channel(a) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

private fun channel(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)

private fun normalizeHue(h: Float): Float = ((h % 360f) + 360f) % 360f
