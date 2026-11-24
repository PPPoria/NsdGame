package com.poria.netnsd.azul

import com.poria.game.azul.AzulPhase
import com.poria.game.azul.Tile
import com.poria.netnsd.link.TcpLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `azul` 频道的**联机**测试：真开 socket（`TcpLink`），一个服务器 + 两个客户端。
 *
 * 覆盖：开局推局面、晚注册的客户端靠 `sync` 追上、走一步两边收敛、越权（替别人走）被拒、
 * 阶段不对被拒。
 *
 * 引擎在 `:game-core` 里（服务器半边的 `AzulChannel` 持有），这里只验"命令 → 规则 → 局面"这条链路。
 */
class AzulOverLinkTest {

    private class Host(val link: TcpLink, val channel: AzulChannel, val port: Int)

    private fun startHost(): Host {
        val link = TcpLink()
        val port = link.host(0)
        return Host(link, AzulChannel(seatOrder = null), port)
    }

    private fun joinGuest(port: Int): Pair<TcpLink, AzulChannel> {
        val link = TcpLink()
        link.join("127.0.0.1", port)
        // 先不注册：等拿到所有人的 id 再建频道，模拟"对局开始时才开频道"
        return link to AzulChannel(seatOrder = null)
    }

    private fun send(link: TcpLink, command: AzulProtocol.Command) {
        link.send(AzulProtocol.NS, AzulProtocol.encode(command))
    }

    private fun await(timeoutMs: Long = 10_000, message: String = "等超时了", block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    @Test(timeout = 40_000)
    fun `开局之后三个人的局面一致，晚注册的靠 sync 追上`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)
        val (second, secondChannel) = joinGuest(host.port)

        try {
            await(message = "报到没完成") { host.link.state.value.players.size == 3 }
            val order = listOf(host.link.playerId, first.playerId, second.playerId)

            // 服务器半边：带上座次（= 出手顺序），注册即开局
            val server = AzulChannel(seatOrder = order)
            assertTrue(host.link.register(server))

            // 客户端半边：注册时会先要一份 sync
            assertTrue(first.register(firstChannel))
            assertTrue(second.register(secondChannel))

            await(message = "客户端没拿到局面") {
                firstChannel.state.value.snapshot != null && secondChannel.state.value.snapshot != null
            }

            val onServer = server.state.value.snapshot!!
            assertEquals("开局就在出牌阶段", AzulPhase.RoundPlaying, onServer.phase)
            assertEquals("第一个动的是座位 1", order[0], onServer.actPlayer)
            assertTrue("工厂盘应该有砖：${onServer.desktop.plates}", onServer.desktop.plates.isNotEmpty())
            assertEquals("三边同一份局面", onServer, firstChannel.state.value.snapshot)
            assertEquals(onServer, secondChannel.state.value.snapshot)
        } finally {
            second.close()
            first.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `走一步：两边都看到同一步，回合交给下一个人`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)

        try {
            await(message = "报到没完成") { host.link.state.value.players.size == 2 }
            val order = listOf(host.link.playerId, first.playerId)
            val server = AzulChannel(seatOrder = order)
            host.link.register(server)
            first.register(firstChannel)

            await(message = "没拿到局面") { firstChannel.state.value.snapshot != null }
            val snapshot = server.state.value.snapshot!!
            val plate = snapshot.desktop.plates.first()
            val tile = plate.tiles.first()

            // 该座位 1 动：他从第一个盘子里拿一块砖放第 0 行
            send(host.link, AzulProtocol.Command.TakeFromPlate(row = 0, tile = tile, plate = plate.tiles))

            await(message = "客户端没收到新局面") {
                firstChannel.state.value.snapshot?.actPlayer == order[1]
            }
            val after = server.state.value.snapshot!!
            assertEquals("回合交给下一个人", order[1], after.actPlayer)
            assertEquals("盘子少了一个", snapshot.desktop.plates.size - 1, after.desktop.plates.size)
            assertEquals(after, firstChannel.state.value.snapshot)
            assertEquals(
                "他第 0 行多了一块",
                1,
                after.desktop.workspaces[order[0]]!!.floor.rows[0]?.second ?: 0,
            )
        } finally {
            first.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `替别人走棋会被拒：只有该动的那个人能操作`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)

        try {
            await(message = "报到没完成") { host.link.state.value.players.size == 2 }
            val order = listOf(host.link.playerId, first.playerId)
            val server = AzulChannel(seatOrder = order)
            host.link.register(server)
            first.register(firstChannel)
            await(message = "没拿到局面") { firstChannel.state.value.snapshot != null }

            val snapshot = server.state.value.snapshot!!
            val plate = snapshot.desktop.plates.first()
            // 轮到座位 1，但座位 2 抢先发命令
            send(first, AzulProtocol.Command.TakeFromPlate(0, plate.tiles.first(), plate.tiles))

            await(message = "没收到拒绝") { firstChannel.state.value.note == "还没轮到你" }
            assertEquals("局面没动", snapshot, server.state.value.snapshot)
        } finally {
            first.close()
            host.link.close()
        }
    }

    @Test(timeout = 60_000)
    fun `一块砖都不剩时服务器自己结算，过一会儿自动开下一轮`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)

        try {
            await(message = "报到没完成") { host.link.state.value.players.size == 2 }
            val order = listOf(host.link.playerId, first.playerId)
            // 结算画面只停 50ms，测试别等 1.5 秒
            val server = AzulChannel(seatOrder = order, roundSettleMs = 50)
            host.link.register(server)
            first.register(firstChannel)
            await(message = "没拿到局面") { firstChannel.state.value.snapshot != null }

            val links = mapOf(order[0] to host.link, order[1] to first)
            // 一直拿砖，直到牌桌空了（引擎自己会判定这一轮结束）
            var moves = 0
            while (server.state.value.snapshot!!.phase == AzulPhase.RoundPlaying && moves < 60) {
                val snapshot = server.state.value.snapshot!!
                val link = links.getValue(snapshot.actPlayer)
                val plate = snapshot.desktop.plates.firstOrNull()
                val command = if (plate != null) {
                    AzulProtocol.Command.TakeFromPlate(0, plate.tiles.first(), plate.tiles)
                } else {
                    AzulProtocol.Command.TakeFromRedundant(0, snapshot.desktop.redundant.first())
                }
                send(link, command)
                moves++
                await(message = "第 $moves 步没生效") {
                    server.state.value.snapshot!!.seq != snapshot.seq
                }
            }

            // 拿完最后一块 → 服务器自己 finishRound，局面进结算
            await(message = "没有自动进结算") {
                server.state.value.snapshot!!.phase == AzulPhase.RoundScore
            }
            // 停一会儿 → 自动开下一轮（或者该结束就进终局结算）
            await(message = "没有自动开下一轮") {
                val phase = server.state.value.snapshot!!.phase
                phase != AzulPhase.RoundScore
            }
            assertEquals("客户端也跟上了", server.state.value.snapshot, firstChannel.state.value.snapshot)
        } finally {
            first.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `局面只信服务器推的：别人伪造的 snap 被丢掉`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)

        try {
            await(message = "报到没完成") { host.link.state.value.players.size == 2 }
            val server = AzulChannel(seatOrder = listOf(host.link.playerId, first.playerId))
            host.link.register(server)
            first.register(firstChannel)
            await(message = "没拿到局面") { firstChannel.state.value.snapshot != null }

            val before = firstChannel.state.value.snapshot!!
            // 冒充"某某帮你带的局面"：落款不是 null
            firstChannel.onEvent(
                AzulProtocol.snapshot(before.copy(actPlayer = first.playerId)),
                from = first.playerId,
            )
            assertEquals("伪造的局面被丢掉", before, firstChannel.state.value.snapshot)
            assertNotNull(server.state.value.snapshot)
        } finally {
            first.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `拿砖时 row 越界这类非法操作会被引擎挡下并回一句原因`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)

        try {
            await(message = "报到没完成") { host.link.state.value.players.size == 2 }
            val server = AzulChannel(seatOrder = listOf(host.link.playerId, first.playerId))
            host.link.register(server)
            first.register(firstChannel)
            await(message = "没拿到局面") { firstChannel.state.value.snapshot != null }

            val before = server.state.value.snapshot!!
            val plate = before.desktop.plates.first()
            // 发命令的是服务器自己那个玩家（房主），所以拒绝原因回到他那一侧的频道上
            send(host.link, AzulProtocol.Command.TakeFromPlate(row = 9, tile = plate.tiles.first(), plate = plate.tiles))

            await(message = "没收到拒绝") { server.state.value.note == "row 必须在 0 到 4 之间" }
            assertEquals("局面没动", before.desktop, server.state.value.snapshot!!.desktop)
        } finally {
            first.close()
            host.link.close()
        }
    }
}
