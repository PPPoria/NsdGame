package com.poria.game.azul

import kotlinx.serialization.Serializable

/** 五种瓷砖 */
enum class Tile {
    Blue, Yellow, Red, Black, White, FirstAct
}

@Serializable
data class AzulDesktop internal constructor(
    val players: Set<String> = emptySet(),
    val plates: List<AzulPlate> = emptyList(),
    val redundant: List<Tile> = emptyList(),
    val workspaces: Map<String, AzulWorkspace> = emptyMap(),
) {
    internal fun placePlates(list: List<AzulPlate>) =
        copy(plates = list) to plates.flatMap { it.tiles }

    internal fun selectTilesFromPlateToRow(
        tile: Tile, plateTiles: List<Tile>,
        player: String, row: Int
    ): AzulDesktop {
        val workspace = workspaces[player]
        check(workspace != null) { "没有这个玩家" }

        val sortedPlateTiles = plateTiles.sorted()
        val selectedPlate = plates.find { it.tiles.sorted() == sortedPlateTiles }
        check(selectedPlate != null) { "没有这个盘子" }
        check(selectedPlate.tiles.contains(tile)) { "盘子里没有匹配的 tile" }

        val newPlates = plates.filter { it !== selectedPlate }
        val newRedundant = redundant + selectedPlate.tiles.filter { it != tile }
        val quantity = selectedPlate.tiles.count { it == tile }
        return copy(
            plates = newPlates,
            redundant = newRedundant,
            workspaces = workspaces + (player to workspace.add(row, tile, quantity))
        )
    }

    internal fun selectTilesFromRedundantToRow(
        tile: Tile,
        player: String, row: Int
    ): AzulDesktop {
        val workspace = workspaces[player]
        check(workspace != null) { "没有这个玩家" }

        val newRedundant = redundant.filter { it != tile }
        val quantity = redundant.count { it == tile }
        return copy(
            redundant = newRedundant,
            workspaces = workspaces + (player to workspace.add(row, tile, quantity))
        )
    }

    internal val nextRoundFirstActPlayer: String? get() = workspaces.entries.find { entry ->
        entry.value.punishment.tiles.contains(Tile.FirstAct)
    }?.key

    internal fun finishRound(): Pair<AzulDesktop, List<Tile>> {
        val rt = ArrayList<Tile>()
        rt.addAll(plates.flatMap { it.tiles })
        rt.addAll(redundant)
        val newWorkspaces = HashMap<String, AzulWorkspace>()
        workspaces.forEach { (player, ws) ->
            val (newWs, wsRt) = ws.finishRound()
            newWorkspaces[player] = newWs
            rt.addAll(wsRt)
        }
        return copy(
            plates = emptyList(),
            redundant = emptyList(),
            workspaces = newWorkspaces
        ) to rt
    }

    internal val shouldFinishGame: Boolean get() = workspaces.any { entry ->
        entry.value.wall.grid.any { list -> list.all { it != null } }
    }

    internal fun finishGame(): AzulDesktop = finishRound().first.let { nd ->
        nd.copy(
            workspaces = nd.workspaces.mapValues {
                it.value.finishGame()
            }
        )
    }

}

@Serializable
data class AzulPlate internal constructor(
    val tiles: List<Tile> = emptyList()
) {
    companion object {
        val plateCntForPlayerCnt = listOf(0, 0, 5, 7, 9)
    }
}

@Serializable
data class AzulWorkspace internal constructor(
    val floor: AzulFloor = AzulFloor(),
    val punishment: AzulPunishment = AzulPunishment(),
    val wall: AzulWall = AzulWall(),
    val score: Int = 0
) {
    internal fun add(row: Int, tile: Tile, quantity: Int): AzulWorkspace {
        val (newFloor, punishmentTiles) = floor.add(row, tile, quantity)
        return copy(
            floor = newFloor,
            punishment = AzulPunishment(punishmentTiles)
        )
    }

    internal fun finishRound(): Pair<AzulWorkspace, List<Tile>> {
        val (newFloor, need, floorRt) = floor.finishRound()
        val (newPunishment, lostScore, punishmentRt) = punishment.finishRound()
        val (newWall, gainScore) = wall.add(need)
        return copy(
            floor = newFloor,
            punishment = newPunishment,
            wall = newWall,
            score = score + lostScore + gainScore
        ) to (floorRt + punishmentRt)
    }

    internal fun finishGame() = copy(
        score = score + wall.specialScore()
    )
}

@Serializable
data class AzulFloor internal constructor(
    val rows: List<Pair<Tile, Int>?> = List(5) { null }
) {
    companion object {
        val maxQuantity = List(5) { it + 1 }
    }

    /**
     * 往 row 行增加 quantity 个 tile
     * 多余的 tile 进入惩罚区
     * @return 新的本类实例，方便 flow 更新
     */
    internal fun add(
        row: Int, tile: Tile, quantity: Int
    ): Pair<AzulFloor, List<Tile>> {
        check(row in 0..4) { "row 必须在 0 到 4 之间" }
        check(quantity >= 0) { "quantity 必须为自然数" }
        val (oriTile, oriQuantity) = rows[row] ?: (tile to 0)
        val (curQuantity, redundant) = if (tile == oriTile) {
            val marge = quantity + oriQuantity
            if (marge > maxQuantity[row]) maxQuantity[row] to (marge - maxQuantity[row])
            else marge to 0
        } else oriQuantity to quantity
        val newRows = rows.toMutableList()
        newRows[row] = oriTile to curQuantity
        return AzulFloor(newRows) to List(redundant) { tile }
    }

    internal fun finishRound(): Triple<AzulFloor, List<Pair<Int, Tile>>, List<Tile>> {
        val need = ArrayList<Pair<Int, Tile>>()
        val rt = ArrayList<Tile>()
        val newRows = rows.toMutableList()
        for (i in rows.indices) {
            val (tile, quantity) = rows[i] ?: continue
            if (quantity != maxQuantity[i]) continue
            newRows[i] = null
            need.add(i to tile)
            rt.addAll(List(maxQuantity[i] - 1) { tile })
        }
        return Triple(copy(rows = newRows), need, rt)
    }
}

@Serializable
data class AzulPunishment internal constructor(
    val tiles: List<Tile> = emptyList()
) {
    companion object {
        val punishment = listOf(-1, -1, -2, -2, -2, -3, -3, -3, -3, -4, -4, -4, -4, -4)
    }

    internal fun finishRound(): Triple<AzulPunishment, Int, List<Tile>> {
        var p = 0
        for (i in tiles.indices) p += punishment[i]
        return Triple(AzulPunishment(), p, tiles)
    }
}

@Serializable
data class AzulWall internal constructor(
    val grid: List<List<Tile?>> = List(5) { List(5) { null } }
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

    internal fun add(list: List<Pair<Int, Tile>>): Pair<AzulWall, Int> {
        var newWall = this
        var gainScore = 0
        list.sortedBy { it.first }.forEach {
            val (w, g) = newWall.add(it.first, it.second)
            newWall = w
            gainScore += g
        }
        return newWall to gainScore
    }

    /** 除了返回新实例，还会返回本次得分 */
    private fun add(row: Int, tile: Tile): Pair<AzulWall, Int> {
        check(row in 0..4) { "row 必须在 0 到 4 之间" }
        check(!grid[row].contains(tile)) { "不允许在同一行嵌入重复的砖块" }
        val col = tileColIdx[tile]!![row]
        val newGrid = grid.toMutableList()
        val newRow = newGrid[row].toMutableList()
        newRow[col] = tile
        newGrid[row] = newRow
        return copy(grid = newGrid) to calculateNewTile(row, col)
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