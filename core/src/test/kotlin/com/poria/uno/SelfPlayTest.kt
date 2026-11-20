package com.poria.uno

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：AI 和 AI 自己打，几百个种子都必须打得完。
 *
 * 目的是防"僵局"——比如牌堆洗完一轮、四家谁也出不了、或者 rule 把回合卡在某个座位上。
 * 这类 bug 手动一局看不出来，扫一遍种子就跑出来了。
 */
class SelfPlayTest {

    @Test
    fun `200 个种子都能打完，最长不超过 500 步`() {
        var worst = 0
        var worstSeed = 0L

        for (seed in 1L..200L) {
            var state = UnoEngine.newGame(playerCount = 4, seed = seed)
            var steps = 0
            while (!state.isFinished && steps < 500) {
                state = UnoEngine.reduce(state, UnoAi.decide(state))
                steps++
            }
            assertTrue(
                "种子 $seed 打了 $steps 步还没结束（手牌 ${state.hands.map { it.size }}，" +
                    "牌堆 ${state.drawPile.size} 张，顶牌 ${state.topCard}）",
                state.isFinished,
            )
            if (steps > worst) {
                worst = steps
                worstSeed = seed
            }
        }

        println("最慢的一局：种子 $worstSeed，$worst 步")
    }

    @Test
    fun `两人局也能打完`() {
        for (seed in 1L..50L) {
            var state = UnoEngine.newGame(playerCount = 2, seed = seed)
            var steps = 0
            while (!state.isFinished && steps < 800) {
                state = UnoEngine.reduce(state, UnoAi.decide(state))
                steps++
            }
            assertTrue("两人局种子 $seed 打不完", state.isFinished)
        }
    }
}
