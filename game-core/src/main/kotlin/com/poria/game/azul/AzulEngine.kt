package com.poria.game.azul

import java.util.HashSet
import java.util.PriorityQueue
import java.util.Random

class AzulEngine(private val playerSeq: Map<String, Int>) {
    private val players by lazy { playerSeq.map { it.key }.toSet() }
    private val seqPlayer: Map<Int, String> by lazy {
        val pq = PriorityQueue<Pair<Int, String>>(compareBy { it.first })
        val hash = HashMap<Int, String>()
        playerSeq.forEach {
            check(hash[it.value] == null) { "每个玩家指定唯一顺序" }
            hash[it.value] = it.key
            pq.offer(it.value to it.key)
        }
        hash.clear()
        var seq = 0
        while (pq.isNotEmpty()) {
            val (_, player) = pq.poll()
            hash[seq++] = player
        }
        hash
    }

    private var isPlaying = false
    private val nextTileList: MutableList<Tile> = mutableListOf()
    private var gameSeq = 0
    private var phaseSeq = 0
    private var gameDesktop = AzulDesktop()

    private fun MutableList<Tile>.gainTile(cnt: Int): List<Tile> {
        val res: MutableList<Tile> = mutableListOf()
        repeat(cnt) {
            if (size == 0) return@repeat
            res.add(nextTileList.removeAt(0))
        }
        return res
    }

    private fun MutableList<Tile>.recycleTiles(tiles: List<Tile>) {
        val random = Random()
        for (t in tiles) {
            add(random.nextInt(size + 1), t)
        }
    }

    /** Options */

    fun startGame(): AzulSnapshot {
        isPlaying = true
        nextTileList.clear()
        nextTileList.addAll(
            Tile.entries
                .flatMap { t -> List(20) { t } }
                .shuffled()
        )
        gameSeq = 0
        phaseSeq = 0
        gameDesktop = AzulDesktop(
            players = players,
            plates = HashSet<AzulPlate>().also {
                repeat(1 + 2 * players.size) { _ ->
                    if (nextTileList.size == 0) return@repeat
                    it.add(AzulPlate(nextTileList.gainTile(4)))
                }
            },
            redundant = emptyList(),
            floors = players.associateWith { AzulFloor() },
            walls = players.associateWith { AzulWall() }
        )
        return AzulSnapshot(
            seq = gameSeq,
            whoPhase = seqPlayer[phaseSeq]!!,
            desktop = gameDesktop
        )
    }

}

data class AzulSnapshot(
    val seq: Int,
    val whoPhase: String,
    val desktop: AzulDesktop
)