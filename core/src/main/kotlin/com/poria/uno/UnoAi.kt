package com.poria.uno

/**
 * 电脑玩家。就是个贪心策略，够用就行——不做搜索、不做概率评估。
 *
 * 规则里唯一带"策略"的地方：万能牌留着别乱打，手里什么颜色多就变成什么颜色。
 */
object UnoAi {

    /** 当前该 [UnoState.turn] 走了，给出一个**保证合法**的动作。 */
    fun decide(state: UnoState): UnoAction {
        val hand = state.hand(state.turn)
        val playable = state.playableCards(state.turn)

        if (playable.isEmpty()) {
            // 摸过了还没得出，就等于放弃本回合。
            return if (state.drewThisTurn) UnoAction.Pass else UnoAction.Draw
        }

        // 优先打普通牌，万能牌留到后面；同分保持手牌顺序，行为可复现。
        val card = playable.minBy { wildCost(it) }
        return UnoAction.Play(
            card = card,
            wildColor = if (card.kind.isWild) favoriteColor(hand) else null,
        )
    }

    /** 手里最多的颜色，万能牌变成它。 */
    fun favoriteColor(hand: List<UnoCard>): UnoColor =
        hand.mapNotNull { it.color }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: UnoColor.RED

    private fun wildCost(card: UnoCard): Int = when (card.kind) {
        UnoKind.WILD4 -> 2
        UnoKind.WILD -> 1
        else -> 0
    }
}
