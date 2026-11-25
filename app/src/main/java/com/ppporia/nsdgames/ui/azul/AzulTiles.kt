package com.ppporia.nsdgames.ui.azul

import com.poria.game.azul.AzulPunishment
import com.poria.game.azul.AzulWall
import com.poria.game.azul.Tile

/**
 * 砖和"棋盘位置"的换算。**只管位置和分值，不管颜色**（颜色在 Activity 那张表里）。
 *
 * 墙面的颜色不是随便定的：`AzulWall.tileColIdx` 决定了"某种颜色在某一行走第几列"，
 * 这里直接读它，保证屏幕上画的位置和引擎算分的位置是同一套。
 * 写死一张表也能跑，但引擎一改就悄悄错位——那种 bug 最难查。
 */
object AzulTiles {

    /** 五种砖，顺序 = [Tile] 枚举顺序（Blue/Yellow/Red/Black/White）。 */
    val colors: List<Tile> = Tile.entries.filter { it != Tile.FirstAct }

    /** 反查表：`cellAt[行][列]` = 那个位置该出现的颜色。 */
    private val cellAt: Array<Array<Tile>> = Array(5) { row ->
        Array(5) { col -> colors.first { columnOf(it, row) == col } }
    }

    /** 某种颜色的砖放在第 [row] 行时走第几列（-1 = 该颜色不在墙上）。 */
    fun columnOf(tile: Tile, row: Int): Int =
        AzulWall.tileColIdx[tile]?.getOrNull(row) ?: -1

    /** 墙面上 (行, 列) 这个位置是什么颜色。 */
    fun tileAt(row: Int, col: Int): Tile = cellAt[row][col]

    /** 惩罚区第 [index] 块砖的分值（负数）。和引擎 `AzulPunishment.punishment` 同一份数据。 */
    fun penaltyAt(index: Int): Int {
        val list = AzulPunishment.punishment
        return list.getOrElse(index) { list.last() }
    }

    /** 一串惩罚区的砖一共要扣多少（非正数）。 */
    fun penaltyOf(count: Int): Int {
        var sum = 0
        for (i in 0 until count) sum += penaltyAt(i)
        return sum
    }
}
