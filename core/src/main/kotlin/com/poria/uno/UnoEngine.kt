package com.poria.uno

import kotlin.random.Random

/**
 * UNO 规则引擎：**这一个文件就是全部规则**，不再拆成十几个 Rule 类。
 *
 * 三条硬约束（以后接服务器时全靠它们）：
 * 1. [reduce] 是纯函数：同样的 `(state, action)` 永远得到同样的 `state`，不读时间、不碰全局随机。
 * 2. 随机只来自 [UnoState.seed]，洗牌一律 `Random(seed)` 派生并把新种子写回 state。
 * 3. 引擎不认识 Android、不认识网络、不认识 UI —— `./gradlew :core:test` 直接跑。
 *
 * 简化掉的规则（个人项目，先不写）：UNO 不喊罚两张、+4 质疑、计分与多局赛制。
 */
object UnoEngine {

    /** 每人初始手牌。 */
    const val HAND_SIZE = 7

    /**
     * 开一局。
     *
     * @param playerCount 2~6 人
     * @param seed 洗牌种子。同一个 seed 永远得到同一局，测试和"悔棋/回放"都靠它
     */
    fun newGame(playerCount: Int = 4, seed: Long = System.nanoTime()): UnoState {
        require(playerCount in 2..6) { "人数只支持 2~6，收到 $playerCount" }
        val random = Random(seed)
        val deck = freshUnoDeck().shuffled(random)

        val hands = List(playerCount) { seat ->
            val from = seat * HAND_SIZE
            deck.subList(from, from + HAND_SIZE).toList()
        }
        val rest = deck.drop(playerCount * HAND_SIZE).toMutableList()

        // 第一张翻开的不能是万能牌（否则开局先手就得选颜色，规则书里也是重新翻一张）。
        val firstIndex = rest.indexOfFirst { !it.kind.isWild }
        check(firstIndex >= 0) { "牌堆里没有可翻开的牌" }
        val first = rest.removeAt(firstIndex)

        return UnoState(
            hands = hands,
            drawPile = rest,
            discardPile = listOf(first),
            topColor = first.color!!, // 上面已经排除了万能牌
            turn = 0,
            seed = seed,
        )
    }

    /**
     * 唯一改状态的地方。非法操作抛 [IllegalMove]。
     *
     * 调用前用 [UnoState.isPlayable] 判断合法性，就能保证只有真正违规才会抛；
     * UI 侧统一 `runCatching` 接一下即可。
     */
    fun reduce(state: UnoState, action: UnoAction): UnoState {
        if (state.isFinished) throw IllegalMove("这局已经结束了")
        return when (action) {
            is UnoAction.Play -> play(state, action)
            UnoAction.Draw -> draw(state)
            UnoAction.Pass -> pass(state)
        }
    }

    private fun play(state: UnoState, action: UnoAction.Play): UnoState {
        val hand = state.hand(state.turn)
        val index = hand.indexOf(action.card)
        if (index < 0) throw IllegalMove("手里没有这张牌")
        if (!state.isPlayable(action.card)) throw IllegalMove("这张牌接不上桌面的颜色和点数")

        val kind = action.card.kind
        val newColor = if (kind.isWild) {
            action.wildColor ?: throw IllegalMove("万能牌必须指定颜色")
        } else {
            action.card.color ?: state.topColor
        }

        val newHand = hand.toMutableList().apply { removeAt(index) }
        var next = state.copy(
            hands = state.hands.toMutableList().apply { set(state.turn, newHand) },
            discardPile = state.discardPile + action.card,
            topColor = newColor,
            clockwise = if (kind == UnoKind.REVERSE) !state.clockwise else state.clockwise,
            drewThisTurn = false,
        )

        // 手牌出完 = 赢。
        if (newHand.isEmpty()) return next.copy(winner = state.turn)

        val direction = if (next.clockwise) 1 else -1

        // 罚牌：下家摸牌，然后被跳过。
        val penalty = when (kind) {
            UnoKind.DRAW2 -> 2
            UnoKind.WILD4 -> 4
            else -> 0
        }
        if (penalty > 0) {
            val victim = seat(state.turn, direction, 1, state.playerCount)
            next = give(next, victim, penalty)
        }

        // 跳过 / 罚牌都让下家少一个回合。
        val steps = if (kind == UnoKind.SKIP || penalty > 0) 2 else 1
        return next.copy(turn = seat(state.turn, direction, steps, next.playerCount))
    }

    private fun draw(state: UnoState): UnoState {
        if (state.drewThisTurn) throw IllegalMove("本回合已经摸过牌了")

        val pile = Pile(state.drawPile, state.discardPile, state.seed)
        val drawn = pile.take(1)
        val after = state.copy(
            drawPile = pile.draw,
            discardPile = pile.discard,
            seed = pile.seed,
        )
        // 牌真的发完了（全在手里）：直接过牌，别把对局卡死。
        if (drawn.isEmpty()) return advance(after)

        val card = drawn.first()
        val withCard = after.copy(
            hands = after.hands.toMutableList().apply { set(state.turn, after.hand(state.turn) + card) },
        )
        // 摸到的牌能出，就留在自己回合（还可以选择 Pass）；不能出就直接过牌。
        return if (withCard.isPlayable(card)) withCard.copy(drewThisTurn = true) else advance(withCard)
    }

    private fun pass(state: UnoState): UnoState {
        if (!state.drewThisTurn) throw IllegalMove("摸牌之后才能过牌")
        return advance(state)
    }

    private fun advance(state: UnoState): UnoState {
        val direction = if (state.clockwise) 1 else -1
        return state.copy(
            turn = seat(state.turn, direction, 1, state.playerCount),
            drewThisTurn = false,
        )
    }

    /** 给 [seat] 发 [count] 张牌。 */
    private fun give(state: UnoState, seat: Int, count: Int): UnoState {
        val pile = Pile(state.drawPile, state.discardPile, state.seed)
        val cards = pile.take(count)
        return state.copy(
            hands = state.hands.toMutableList().apply { set(seat, state.hand(seat) + cards) },
            drawPile = pile.draw,
            discardPile = pile.discard,
            seed = pile.seed,
        )
    }

    /** 从 [from] 出发走 [steps] 步后的座位号，负数也能正确回绕。 */
    private fun seat(from: Int, direction: Int, steps: Int, count: Int): Int =
        (((from + direction * steps) % count) + count) % count

    /**
     * 牌堆 + 弃牌堆。牌堆空了就把弃牌堆（留顶牌）洗回来。
     *
     * 写成小类是为了让"洗牌重发"只出现一次；它仍然是纯的——随机数全部来自 [seed]。
     */
    private class Pile(var draw: List<UnoCard>, var discard: List<UnoCard>, var seed: Long) {

        fun take(count: Int): List<UnoCard> {
            val out = ArrayList<UnoCard>(count)
            while (out.size < count) {
                if (draw.isEmpty()) refill()
                if (draw.isEmpty()) break // 一张都不剩了，能发几张发几张
                out += draw.first()
                draw = draw.drop(1)
            }
            return out
        }

        private fun refill() {
            if (discard.size <= 1) return
            val random = Random(seed)
            seed = random.nextLong()
            draw = discard.dropLast(1).shuffled(random)
            discard = listOf(discard.last())
        }
    }
}
