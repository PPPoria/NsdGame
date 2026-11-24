package com.poria.game.azul

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.util.Random

class AzulEngine {
    private var seqPlayer: List<String> = emptyList()
    private val nextTileList: MutableList<Tile> = mutableListOf()
    private val _state = MutableStateFlow(AzulSnapshot())

    /** private function */

    private fun gainTile(cnt: Int = 4): List<Tile> {
        val res: MutableList<Tile> = mutableListOf()
        repeat(cnt) {
            if (nextTileList.size == 0) return@repeat
            res.add(nextTileList.removeAt(0))
        }
        return res
    }

    private fun recycleTiles(tiles: List<Tile>) {
        val random = Random()
        for (t in tiles) {
            if (t == Tile.FirstAct) continue
            nextTileList.add(random.nextInt(nextTileList.size + 1), t)
        }
    }

    private fun nextPlayer(cur: String): String =
        seqPlayer[(seqPlayer.indexOf(cur) + 1) % seqPlayer.size]

    private fun canFinishRound(snapshot: AzulSnapshot): Boolean = snapshot.desktop.run {
        plates.isEmpty() && redundant.isEmpty()
    } && snapshot.phase == AzulPhase.RoundPlaying

    private fun canFinishGame(snapshot: AzulSnapshot): Boolean {
        if (snapshot.phase != AzulPhase.RoundScore) return false
        if (snapshot.desktop.shouldFinishGame) return true
        val (_, tryList) = snapshot.desktop.finishRound()
        return tryList.isEmpty() && nextTileList.isEmpty()
    }

    /** Api */

    val state = _state.asStateFlow()
    val availableTileCnt: Int get() = nextTileList.size
    val nowCanFinishRound: Boolean get() = canFinishRound(_state.value)
    val nowNextRoundFirstActPlayer: String? get() = _state.value.desktop.nextRoundFirstActPlayer

    fun prepareNewGame(players: Set<String>) {
        check(players.size in 2..4) { "游戏玩家数量必须为 2 到 4 人" }
        nextTileList.clear()
        nextTileList.addAll(
            Tile.entries
                .filter { it != Tile.FirstAct }
                .flatMap { t -> List(20) { t } }
                .shuffled()
        )
        _state.update { _ ->
            val gameDesktop = AzulDesktop(
                players = players,
                plates = emptyList(),
                redundant = emptyList(),
                workspaces = players.associateWith { AzulWorkspace() }
            )
            AzulSnapshot(
                seq = 0,
                phase = AzulPhase.Preparing,
                actPlayer = "",
                desktop = gameDesktop
            )
        }
    }

    fun startGame(seqPlayers: List<String>) = _state.update { old ->
        check(old.phase != AzulPhase.Preparing) { "必须在准备阶段后才能开始游戏" }
        check(old.desktop.players.sorted() == seqPlayers.sorted()) { "和准备时的玩家不匹配" }
        this.seqPlayer = seqPlayers
        val (desktop, rt) = old.desktop.placePlates(
            ArrayList<AzulPlate>().also {
                repeat(AzulPlate.plateCntForPlayerCnt[seqPlayer.size]) { _ ->
                    if (nextTileList.size == 0) return@repeat
                    it.add(AzulPlate(gainTile()))
                }
            }
        )
        recycleTiles(rt)
        old.copy(
            seq = old.seq + 1,
            phase = AzulPhase.RoundPlaying,
            actPlayer = seqPlayer[0],
            desktop = desktop
        )
    }

    fun selectTilesFromPlateToRow(
        tile: Tile, plateTiles: List<Tile>,
        player: String, row: Int
    ) = _state.update { old ->
        check(old.phase == AzulPhase.RoundPlaying) { "必须在 RoundPlaying 执行" }
        check(!canFinishRound(old)) { "desktop 上没有可以拿的 tile" }
        val desktop = old.desktop.selectTilesFromPlateToRow(tile, plateTiles, player, row)
        old.copy(
            seq = old.seq + 1,
            actPlayer = nextPlayer(old.actPlayer),
            desktop = desktop
        )
    }

    fun selectTilesFromRedundantToRow(
        tile: Tile,
        player: String, row: Int
    ) = _state.update { old ->
        check(old.phase == AzulPhase.RoundPlaying) { "必须在 RoundPlaying 执行" }
        check(!canFinishRound(old)) { "desktop 上没有可以拿的 tile" }
        val desktop = old.desktop.selectTilesFromRedundantToRow(tile, player, row)
        old.copy(
            seq = old.seq + 1,
            actPlayer = nextPlayer(old.actPlayer),
            desktop = desktop
        )
    }

    fun finishRound() = _state.update { old ->
        check(old.phase == AzulPhase.RoundPlaying) { "必须在 RoundPlaying 执行" }
        check(canFinishRound(old)) { "还有可以拿的 tile" }
        val firstAct = old.desktop.nextRoundFirstActPlayer ?: seqPlayer[0]
        seqPlayer = ArrayList<String>().apply {
            val target = seqPlayer.indexOf(firstAct)
            for (i in target until seqPlayer.size) add(seqPlayer[i])
            for (i in 0 until target) add(seqPlayer[i])
        }
        val (desktop, rt) = old.desktop.finishRound()
        recycleTiles(rt)
        old.copy(
            seq = old.seq + 1,
            phase = AzulPhase.RoundScore,
            actPlayer = firstAct,
            desktop = desktop
        )
    }

    fun nextRoundOrFinishGame() = _state.update { old ->
        check(old.phase == AzulPhase.RoundScore) { "必须在 RoundScore 执行" }
        val nextRound = old.copy(
            seq = old.seq + 1,
            phase = AzulPhase.RoundPlaying
        )
        if (!canFinishGame(old)) return@update nextRound
        nextRound.copy(
            phase = AzulPhase.EndSpecialScore,
            desktop = old.desktop.finishGame()
        )
    }
}

@Serializable
data class AzulSnapshot internal constructor(
    val seq: Int = 0,
    val phase: AzulPhase = AzulPhase.Preparing,
    val actPlayer: String = "",
    val desktop: AzulDesktop = AzulDesktop()
)

enum class AzulPhase {
    Preparing,
    RoundPlaying,
    RoundScore,
    EndSpecialScore,
}