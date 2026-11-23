package com.poria.game.azul

/** 五种瓷砖 */
enum class Tile {
    Blue, Yellow, Red, Black, White
}

data class AzulDesktop internal constructor(
    val players: Set<String> = emptySet(),
    val plates: Set<AzulPlate> = emptySet(),
    val redundant: List<Tile> = emptyList(),
    val floors: Map<String, AzulFloor> = emptyMap(),
    val walls: Map<String, AzulWall> = emptyMap(),
) {

}

data class AzulPlate internal constructor(
    val tiles: List<Tile> = emptyList()
) {
    internal fun selectKind(tile: Tile): Result {
        check(tiles.contains(tile)) { "不能选择盘子中没有的砖块类型" }
        return Result(
            selected = tiles.filter { it == tile },
            others = tiles.filter { it != tile }
        )
    }

    internal data class Result(
        val selected: List<Tile>,
        val others: List<Tile>
    )
}

data class AzulFloor internal constructor(
    val rows: Map<Int, Pair<Tile, Int>> = emptyMap(), // 0..4
    val punishment: AzulPunishment = AzulPunishment()
) {
    companion object {
        val maxQuantity = List(5) { it + 1 }
    }

    /**
     * 往 row 行增加 quantity 个 tile
     * 多余的 tile 进入惩罚区
     * @return 新的本类实例，方便 flow 更新
     */
    internal fun add(row: Int, tile: Tile, quantity: Int): AzulFloor {
        check(row in 0..4) { "row 必须在 0 到 4 之间" }
        check(quantity >= 0) { "quantity 必须为自然数" }
        val (oriTile, oriQuantity) = rows[row] ?: (tile to 0)
        val (curQuantity, redundant) = if (tile == oriTile) {
            val marge = quantity + oriQuantity
            if (marge > maxQuantity[row]) (row + 1) to (marge - maxQuantity[row])
            else marge to 0
        } else oriQuantity to quantity
        return AzulFloor(
            rows + (row to (oriTile to curQuantity)),
            punishment.add(tile, redundant)
        )
    }
}

data class AzulPunishment internal constructor(
    val tiles: List<Tile> = emptyList(),
    val sum: Int = 0
) {
    companion object {
        val punishment = listOf(-1, -1, -2, -2, -2, -3, -3, -3, -3, -4, -4, -4, -4, -4)
    }

    internal fun add(tile: Tile, quantity: Int): AzulPunishment {
        check(quantity >= 0) { "quantity 必须为自然数" }
        val newTiles = tiles + List(quantity) { tile }
        var newSum = 0
        for (i in newTiles.indices) newSum += punishment[i]
        return AzulPunishment(newTiles, newSum)
    }
}

data class AzulWall internal constructor(
    val grid: List<Array<Tile?>> = List(5) { Array(5) { null } },
    val commonScore: Int = 0
) {
    companion object {
        val tileColIdx = listOf( // 不使用 .entries 穷举是因为担心顺序改变
            Tile.Blue,
            Tile.Yellow,
            Tile.Red,
            Tile.Black,
            Tile.White
        ).mapIndexed { offset, tile ->
            tile to List(5) { (it + offset) % 5 }
        }.toMap()
    }

    internal fun specialScore(): Int {
        val sameCnt = HashMap<Tile, Int>()
        for (i in 0..4) {
            for (j in 0..4) {
                grid[i][j]?.let { tile ->
                    sameCnt[tile] = (sameCnt[tile] ?: 0) + 1
                }
            }
        }
        val rowFull = Array(5) { true }
        for (i in 0..4) {
            for (j in 0..4) {
                rowFull[i] = rowFull[i] and (grid[i][j] != null)
            }
        }
        val colFull = Array(5) { true }
        for (j in 0..4) {
            for (i in 0..4) {
                colFull[j] = colFull[j] and (grid[i][j] != null)
            }
        }
        return sameCnt.count { it.value == 5 } + rowFull.count { it } + colFull.count { it }
    }

    internal fun add(row: Int, tile: Tile): AzulWall {
        check(row in 0..4) { "row 必须在 0 到 4 之间" }
        check(!grid[row].contains(tile)) { "不允许在同一行嵌入重复的砖块" }
        val col = tileColIdx[tile]!![row]
        grid[row][col] = tile
        val sum = commonScore + calculateNewTile(row, col)
        return copy(commonScore = sum)
    }

    private fun calculateNewTile(row: Int, col: Int): Int {
        var rowScore = 1
        for (j in col + 1..4) if (grid[row][j] != null) rowScore++ else break
        for (j in col - 1 downTo 0) if (grid[row][j] != null) rowScore++ else break
        var colScore = 1
        for (i in row + 1..4) if (grid[i][col] != null) colScore++ else break
        for (i in row - 1 downTo 0) if (grid[i][col] != null) colScore++ else break
        return rowScore + colScore
    }
}