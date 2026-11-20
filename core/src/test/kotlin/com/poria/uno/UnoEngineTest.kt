package com.poria.uno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核单测。`./gradlew :core:test` 几秒钟跑完，不需要模拟器。
 *
 * 这里只测"规则对不对"，不测 UI；对局类的测试全部从手搓的 [stateOf] 出发，
 * 不靠随机发牌碰运气。
 */
class UnoEngineTest {

    // ── 牌堆 ────────────────────────────────────────────────────────────────

    @Test
    fun `一副牌 108 张且每色 25 张`() {
        val deck = freshUnoDeck()
        assertEquals(108, deck.size)
        UnoColor.entries.forEach { color ->
            assertEquals("颜色 $color 的张数", 25, deck.count { it.color == color })
        }
        assertEquals(4, deck.count { it.kind == UnoKind.WILD })
        assertEquals(4, deck.count { it.kind == UnoKind.WILD4 })
        assertEquals("0 每种颜色只有一张", 4, deck.count { it.kind == UnoKind.N0 })
    }

    // ── 开局 ────────────────────────────────────────────────────────────────

    @Test
    fun `同一个种子开出同一局`() {
        assertEquals(UnoEngine.newGame(seed = 42), UnoEngine.newGame(seed = 42))
    }

    @Test
    fun `开局每人七张且牌不丢`() {
        val game = UnoEngine.newGame(playerCount = 4, seed = 7)
        assertEquals(4, game.playerCount)
        game.hands.forEach { assertEquals(UnoEngine.HAND_SIZE, it.size) }
        assertEquals(
            "所有牌数加起来应该还是 108",
            108,
            game.hands.sumOf { it.size } + game.drawPile.size + game.discardPile.size,
        )
        assertFalse("翻开的第一张不能是万能牌", game.topCard.kind.isWild)
        assertEquals(game.topCard.color, game.topColor)
        assertEquals(0, game.turn)
    }

    // ── 出牌 ────────────────────────────────────────────────────────────────

    @Test
    fun `接不上颜色的牌不能出`() {
        val state = stateOf(hand = listOf(card(UnoColor.BLUE, UnoKind.N5)), top = card(UnoColor.RED, UnoKind.N3))
        expectIllegal { UnoEngine.reduce(state, UnoAction.Play(card(UnoColor.BLUE, UnoKind.N5))) }
    }

    @Test
    fun `同点数的牌可以出`() {
        val blue5 = card(UnoColor.BLUE, UnoKind.N5)
        val state = stateOf(hand = listOf(blue5, card(UnoColor.GREEN, UnoKind.N1)), top = card(UnoColor.RED, UnoKind.N5))

        val next = UnoEngine.reduce(state, UnoAction.Play(blue5))

        assertEquals(listOf(card(UnoColor.GREEN, UnoKind.N1)), next.hand(0))
        assertEquals(UnoColor.BLUE, next.topColor)
        assertEquals(1, next.turn)
    }

    @Test
    fun `出牌后手牌清空即获胜`() {
        val red7 = card(UnoColor.RED, UnoKind.N7)
        val state = stateOf(hand = listOf(red7), top = card(UnoColor.RED, UnoKind.N2))

        val next = UnoEngine.reduce(state, UnoAction.Play(red7))

        assertEquals(0, next.winner)
        assertTrue(next.isFinished)
        assertTrue(next.hand(0).isEmpty())
    }

    @Test
    fun `对局结束后不能再出牌`() {
        val state = stateOf(hand = listOf(card(UnoColor.RED, UnoKind.N7)), top = card(UnoColor.RED, UnoKind.N2))
            .copy(winner = 2)
        expectIllegal { UnoEngine.reduce(state, UnoAction.Draw) }
    }

    // ── 功能牌 ──────────────────────────────────────────────────────────────

    @Test
    fun `跳过让下家少一个回合`() {
        val skip = card(UnoColor.RED, UnoKind.SKIP)
        val state = stateOf(hand = listOf(skip, red5), top = card(UnoColor.RED, UnoKind.N1))

        val next = UnoEngine.reduce(state, UnoAction.Play(skip))

        assertEquals("跳过两人后轮到座位 2", 2, next.turn)
    }

    @Test
    fun `反转改变方向`() {
        val reverse = card(UnoColor.RED, UnoKind.REVERSE)
        val state = stateOf(hand = listOf(reverse, red5), top = card(UnoColor.RED, UnoKind.N1))

        val next = UnoEngine.reduce(state, UnoAction.Play(reverse))

        assertFalse("方向应该反过来了", next.clockwise)
        assertEquals("逆时针时下家是座位 3", 3, next.turn)
    }

    @Test
    fun `加二让下家摸两张并被跳过`() {
        val draw2 = card(UnoColor.RED, UnoKind.DRAW2)
        val state = stateOf(hand = listOf(draw2, red5), top = card(UnoColor.RED, UnoKind.N1))

        val next = UnoEngine.reduce(state, UnoAction.Play(draw2))

        assertEquals(UnoEngine.HAND_SIZE + 2, next.hand(1).size)
        assertEquals("摸两张后还是被跳过", 2, next.turn)
    }

    @Test
    fun `变色牌必须指定颜色`() {
        val wild = card(null, UnoKind.WILD)
        val state = stateOf(hand = listOf(wild, red5), top = card(UnoColor.RED, UnoKind.N1))

        expectIllegal { UnoEngine.reduce(state, UnoAction.Play(wild)) }

        val next = UnoEngine.reduce(state, UnoAction.Play(wild, UnoColor.GREEN))
        assertEquals(UnoColor.GREEN, next.topColor)
        assertEquals("变色牌不改变出牌顺序", 1, next.turn)
        // 新颜色生效：绿色的牌能出，红色的不能。
        assertTrue(next.isPlayable(card(UnoColor.GREEN, UnoKind.N9)))
        assertFalse(next.isPlayable(card(UnoColor.RED, UnoKind.N9)))
    }

    @Test
    fun `加四让下家摸四张并被跳过`() {
        val wild4 = card(null, UnoKind.WILD4)
        val state = stateOf(hand = listOf(wild4, red5), top = card(UnoColor.RED, UnoKind.N1))

        val next = UnoEngine.reduce(state, UnoAction.Play(wild4, UnoColor.BLUE))

        assertEquals(UnoColor.BLUE, next.topColor)
        assertEquals(UnoEngine.HAND_SIZE + 4, next.hand(1).size)
        assertEquals(2, next.turn)
    }

    // ── 摸牌与过牌 ──────────────────────────────────────────────────────────

    @Test
    fun `摸到能出的牌就留在自己回合`() {
        val red6 = card(UnoColor.RED, UnoKind.N6)
        val state = stateOf(hand = listOf(card(UnoColor.BLUE, UnoKind.N1)), top = card(UnoColor.RED, UnoKind.N1), pile = listOf(red6))

        val next = UnoEngine.reduce(state, UnoAction.Draw)

        assertTrue("摸到能出的牌", next.drewThisTurn)
        assertEquals("还轮到自己", 0, next.turn)
        assertTrue(next.hand(0).contains(red6))
    }

    @Test
    fun `摸到不能出的牌就直接过牌`() {
        val blue6 = card(UnoColor.BLUE, UnoKind.N6)
        val state = stateOf(hand = listOf(card(UnoColor.BLUE, UnoKind.N1)), top = card(UnoColor.RED, UnoKind.N1), pile = listOf(blue6))

        val next = UnoEngine.reduce(state, UnoAction.Draw)

        assertFalse(next.drewThisTurn)
        assertEquals(1, next.turn)
    }

    @Test
    fun `摸牌后可以过牌，过牌后轮下家`() {
        val state = stateOf(
            hand = listOf(card(UnoColor.RED, UnoKind.N6)),
            top = card(UnoColor.RED, UnoKind.N1),
            pile = listOf(card(UnoColor.RED, UnoKind.N9)),
        ).let { UnoEngine.reduce(it, UnoAction.Draw) }

        val next = UnoEngine.reduce(state, UnoAction.Pass)

        assertEquals(1, next.turn)
        assertFalse(next.drewThisTurn)
    }

    @Test
    fun `没摸牌不能过牌`() {
        val state = stateOf(hand = listOf(card(UnoColor.RED, UnoKind.N6)), top = card(UnoColor.RED, UnoKind.N1))
        expectIllegal { UnoEngine.reduce(state, UnoAction.Pass) }
    }

    @Test
    fun `一回合只能摸一次牌`() {
        val state = stateOf(
            hand = listOf(card(UnoColor.RED, UnoKind.N6)),
            top = card(UnoColor.RED, UnoKind.N1),
            pile = listOf(card(UnoColor.RED, UnoKind.N9)),
        ).let { UnoEngine.reduce(it, UnoAction.Draw) }

        expectIllegal { UnoEngine.reduce(state, UnoAction.Draw) }
    }

    @Test
    fun `牌堆摸空会把弃牌洗回来`() {
        val state = stateOf(
            hand = listOf(card(UnoColor.BLUE, UnoKind.N1)),
            top = card(UnoColor.RED, UnoKind.N1),
            pile = emptyList(),
            extraDiscard = listOf(card(UnoColor.GREEN, UnoKind.N2), card(UnoColor.GREEN, UnoKind.N3)),
        )

        val next = UnoEngine.reduce(state, UnoAction.Draw)

        assertEquals("洗回来之后还能摸到牌", 2, next.hand(0).size)
        assertEquals("顶牌不变", card(UnoColor.RED, UnoKind.N1), next.topCard)
    }

    // ── AI 自对弈 ───────────────────────────────────────────────────────────

    @Test
    fun `AI 自己跟自己打得完一局且从不违规`() {
        var state = UnoEngine.newGame(playerCount = 4, seed = 2024)
        var steps = 0

        while (!state.isFinished && steps < 4000) {
            // reduce 遇到非法动作会抛 IllegalMove，跑得通就说明 AI 一直合法。
            state = UnoEngine.reduce(state, UnoAi.decide(state))
            steps++
        }

        assertTrue("AI 应该能在有限步内打完一局，实际走了 $steps 步", state.isFinished)
        val winner = state.winner
        assertNotNull(winner)
        assertTrue("赢家手牌应该为空", state.hand(winner!!).isEmpty())
    }

    @Test
    fun `AI 会留着万能牌先打普通牌`() {
        val hand = listOf(card(null, UnoKind.WILD), card(UnoColor.RED, UnoKind.N3))
        val state = stateOf(hand = hand, top = card(UnoColor.RED, UnoKind.N1))

        val action = UnoAi.decide(state)

        assertEquals(UnoAction.Play(card(UnoColor.RED, UnoKind.N3), null), action)
    }

    @Test
    fun `AI 变色的颜色是手里最多的那种`() {
        val hand = listOf(
            card(null, UnoKind.WILD),
            card(UnoColor.GREEN, UnoKind.N1),
            card(UnoColor.GREEN, UnoKind.N2),
            card(UnoColor.BLUE, UnoKind.N3),
        )

        assertEquals(UnoColor.GREEN, UnoAi.favoriteColor(hand))
    }

    // ── 测试辅助 ────────────────────────────────────────────────────────────

    private fun card(color: UnoColor?, kind: UnoKind) = UnoCard(color, kind)

    private val red5 = card(UnoColor.RED, UnoKind.N5)

    /** 手搓一个状态：座位 0 是自己，其余座位是固定的满手牌。 */
    private fun stateOf(
        hand: List<UnoCard>,
        top: UnoCard,
        pile: List<UnoCard> = defaultPile,
        extraDiscard: List<UnoCard> = emptyList(),
        turn: Int = 0,
        others: Int = 3,
    ) = UnoState(
        hands = listOf(hand) + List(others) { List(UnoEngine.HAND_SIZE) { card(UnoColor.BLUE, UnoKind.N1) } },
        drawPile = pile,
        discardPile = extraDiscard + top,
        topColor = top.color ?: UnoColor.RED,
        turn = turn,
        seed = 99L,
    )

    private val defaultPile = List(20) { card(UnoColor.YELLOW, UnoKind.N4) }

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalMove) {
            return
        }
        throw AssertionError("应该抛 IllegalMove，但没有")
    }
}
