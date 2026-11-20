package com.poria.uno

/**
 * 一局 UNO 的完整状态。不可变：所有字段都是 `List` / `Int` / `Boolean`，没有任何可变共享。
 *
 * 为什么只有这一份状态就够：规则判定全在 [UnoEngine.reduce] 里，UI 只是把它画出来。
 * 以后接服务器时，"服务端权威"就是服务端持有它、按玩家裁剪 [hands] 后下发，
 * 客户端不自己算结果——所以这里不需要额外的事件流、哈希、快照协议。
 *
 * [hands] 的下标就是座位号；[UnoState.hand] 取手牌。
 */
data class UnoState(
    /** 每个座位的手牌。 */
    val hands: List<List<UnoCard>>,
    /** 牌堆（下标 0 是堆顶）。 */
    val drawPile: List<UnoCard>,
    /** 弃牌堆，末尾是桌面顶牌。 */
    val discardPile: List<UnoCard>,
    /** 当前生效颜色。打出万能牌后是玩家选的颜色，否则等于 [topCard] 的颜色。 */
    val topColor: UnoColor,
    /** 该谁出牌（座位号）。 */
    val turn: Int,
    /** 出牌方向：true 顺时针（座位号递增），false 逆时针。 */
    val clockwise: Boolean = true,
    /** 本回合是否已经摸过牌（摸到能出的牌时才用得上：可以出，也可以 [UnoAction.Pass]）。 */
    val drewThisTurn: Boolean = false,
    /** 赢家座位号；`null` 表示还在打。 */
    val winner: Int? = null,
    /** 洗牌种子。放状态里是为了让 [UnoEngine.reduce] 保持纯函数、可复现。 */
    val seed: Long = 0L,
) {
    /** 桌面顶牌。 */
    val topCard: UnoCard get() = discardPile.last()

    val playerCount: Int get() = hands.size

    val isFinished: Boolean get() = winner != null

    fun hand(seat: Int): List<UnoCard> = hands[seat]

    /** 这张牌现在能不能出：同色、同点数/同功能，或者万能牌。 */
    fun isPlayable(card: UnoCard): Boolean =
        card.kind.isWild || card.color == topColor || card.kind == topCard.kind

    /** 这一手能出的所有牌（给 UI 高亮、给 AI 选牌用）。 */
    fun playableCards(seat: Int): List<UnoCard> = hand(seat).filter(::isPlayable)
}
