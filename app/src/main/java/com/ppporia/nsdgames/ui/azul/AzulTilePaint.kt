package com.ppporia.nsdgames.ui.azul

import android.graphics.Bitmap
import androidx.annotation.ColorInt

/**
 * 一块砖"长什么样"：底色 + 可选花纹图 + 可选小字。
 *
 * **颜色只有一个来源**：对局页里那张 `Map<Tile, AzulTilePaint>`（[AzulGameActivity]）。
 * 各个容器只负责"把 paint 涂上去"，不认识 Tile，也不认识任何具体颜色。
 *
 * 好处：想换配色、想给某种砖上纹理（`builtin:azul.*`）都只改 Activity 那一张表；
 * 空位的淡色、先手砖上的「先」字，也都是 Activity 生成好一份 paint 传进来，容器不用判断。
 */
data class AzulTilePaint(
    @ColorInt val color: Int,
    /** 花纹层；null = 纯色块。 */
    val bitmap: Bitmap? = null,
    /** 盖在砖上的小字（比如先手砖的「先」）；null = 不写字。 */
    val label: String? = null,
)
