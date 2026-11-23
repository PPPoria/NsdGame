package com.poria.netnsd.lobby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大厅规则的纯逻辑测试：**不碰 socket、不碰协程**，只对着 `snapshot()` 和返回值断言。
 *
 * 覆盖：排座、满员、重复进房、聊天、准备、换座、改人数、换游戏、玩法开关、踢人、开局的校验。
 * 昵称和头像不在这层（它们属于 `profile` 频道），所以这里一个人名都不出现。
 */
class LobbyTest {

    private fun lobbyWith(vararg players: Pair<String, Boolean>): Lobby {
        val lobby = Lobby("测试房间")
        players.forEach { (id, host) -> lobby.join(id, host) }
        return lobby
    }

    private fun Lobby.seats(): List<Pair<String, Int>> = snapshot().players.map { it.id to it.seat }

    // ── 进出房间 ────────────────────────────────────────────────────────────

    @Test
    fun `进房按最小空位排座，房主标记来自链路`() {
        val lobby = Lobby("测试房间")
        assertEquals(Lobby.JoinResult.Joined, lobby.join("p1", host = true))
        assertEquals(Lobby.JoinResult.Joined, lobby.join("p2", host = false))

        val snapshot = lobby.snapshot()
        assertEquals(listOf("p1" to 0, "p2" to 1), snapshot.players.map { it.id to it.seat })
        assertEquals("p1", snapshot.hostId)
        assertTrue(snapshot.isHost("p1"))
        assertEquals("快照里只有身份和座位", LobbyPlayer("p2", 1), snapshot.player("p2"))
    }

    @Test
    fun `离开会释放座位`() {
        val lobby = lobbyWith("p1" to true, "p2" to false, "p3" to false)
        assertTrue(lobby.leave("p2"))
        assertEquals(listOf("p1" to 0, "p3" to 2), lobby.seats())

        assertEquals(Lobby.JoinResult.Joined, lobby.join("p4", host = false))
        assertEquals("新来的补到空出来的 1 号位", listOf("p1" to 0, "p4" to 1, "p3" to 2), lobby.seats())
    }

    @Test
    fun `座位满了进不来`() {
        val lobby = lobbyWith("p1" to true, "p2" to false, "p3" to false, "p4" to false)
        assertEquals(4, lobby.snapshot().tableSize)
        assertEquals(Lobby.JoinResult.Full, lobby.join("p5", host = false))
        assertEquals("满了什么都没改", 4, lobby.size)
    }

    @Test
    fun `同一个 id 重复进房、空 id 都被拒，而且什么都没改`() {
        val lobby = lobbyWith("p1" to true)
        val version = lobby.version
        assertEquals(Lobby.JoinResult.AlreadyJoined, lobby.join("p1", host = true))
        assertEquals(Lobby.JoinResult.InvalidId, lobby.join("  ", host = false))
        assertEquals(version, lobby.version)
        assertEquals(1, lobby.size)
    }

    // ── 聊天 / 准备 ─────────────────────────────────────────────────────────

    @Test
    fun `说的話进快照，空消息不改版本`() {
        val lobby = lobbyWith("p1" to true)
        val before = lobby.version
        assertNull(lobby.say("p1", "开一局？"))
        assertTrue(lobby.version > before)

        val after = lobby.version
        assertNull("空消息不算错", lobby.say("p1", "   "))
        assertEquals(after, lobby.version)

        val message = lobby.snapshot().messages.single()
        assertEquals(LobbyMessage(1, "p1", "开一局？"), message)
    }

    @Test
    fun `不在房间里的人说不了话、准备不了、坐不了座`() {
        val lobby = lobbyWith("p1" to true)
        assertEquals("你不在房间里了", lobby.say("p9", "我是谁"))
        assertEquals("你不在房间里了", lobby.setReady("p9", true))
        assertEquals("你不在房间里了", lobby.takeSeat("p9", 1))
    }

    @Test
    fun `准备状态决定能不能开，改玩法会清空准备`() {
        val lobby = lobbyWith("p1" to true, "p2" to false)
        assertEquals("还有 1 人没准备", lobby.snapshot().startHint)

        assertNull(lobby.setReady("p2", true))
        assertEquals("", lobby.snapshot().startHint)
        assertEquals(listOf("p2"), lobby.snapshot().readyIds)

        assertNull(lobby.setOption("p1", "grey", true))
        assertEquals("改玩法之后都回到没准备", emptyList<String>(), lobby.snapshot().readyIds)
        assertEquals("还有 1 人没准备", lobby.snapshot().startHint)
    }

    // ── 座位 ────────────────────────────────────────────────────────────────

    @Test
    fun `自己只能坐空座位，调座次是房主的权限`() {
        val lobby = lobbyWith("p1" to true, "p2" to false)

        assertEquals("那个座位有人了", lobby.takeSeat("p2", 0))
        assertNull(lobby.takeSeat("p2", 3))
        assertEquals(listOf("p1" to 0, "p2" to 3), lobby.seats())

        assertEquals("只有房主能改设置", lobby.swapSeats("p2", 0, 3))
        assertNull(lobby.swapSeats("p1", 0, 3))
        assertEquals("快照按座位排序", listOf("p2" to 0, "p1" to 3), lobby.seats())

        assertEquals("没有 9 号座位", lobby.takeSeat("p2", 9))
    }

    @Test
    fun `改人数：不能少于在场人数，改小以后越界的人自动挪位`() {
        val lobby = lobbyWith("p1" to true, "p2" to false)
        assertEquals("没变就是空操作", null, lobby.setTableSize("p1", 4))
        assertEquals("「花砖物语」只能 2–4 人", lobby.setTableSize("p1", 6))

        assertNull(lobby.setTableSize("p1", 2))
        assertEquals(listOf("p1" to 0, "p2" to 1), lobby.seats())

        // 先坐到最后一位，再把牌桌缩小
        assertNull(lobby.setTableSize("p1", 4))
        assertNull(lobby.takeSeat("p2", 3))
        assertNull(lobby.setTableSize("p1", 2))
        assertEquals("越界的人被挪回牌桌内", listOf("p1" to 0, "p2" to 1), lobby.seats())
    }

    // ── 游戏 / 玩法 ─────────────────────────────────────────────────────────

    @Test
    fun `换游戏：同一个游戏是空操作，认不出的游戏要拒绝`() {
        val lobby = lobbyWith("p1" to true)
        val before = lobby.version
        assertNull(lobby.selectGame("p1", "azul"))
        assertEquals(before, lobby.version)
        assertEquals("没有这个游戏：nope", lobby.selectGame("p1", "nope"))
        assertEquals("非房主改不了", "只有房主能改设置", lobby.selectGame("p2", "azul"))
    }

    // ── 踢人 / 开局 ─────────────────────────────────────────────────────────

    @Test
    fun `踢人把人移出房间，非房主和踢自己都拒绝`() {
        val lobby = lobbyWith("p1" to true, "p2" to false)
        assertEquals("只有房主能改设置", lobby.kick("p2", "p1"))
        assertEquals("不能把自己请出去", lobby.kick("p1", "p1"))
        assertNull(lobby.kick("p1", "p2"))
        assertEquals(listOf("p1" to 0), lobby.seats())
        assertEquals("这个人已经不在房间里了", lobby.kick("p1", "p2"))
    }

    @Test
    fun `房主按开始：人数不够、有人没准备、规则还没写`() {
        val lobby = Lobby("测试房间")
        lobby.join("p1", host = true)
        assertEquals("只有房主能改设置", lobby.start("p2"))
        assertEquals("还差 1 人", lobby.start("p1"))

        lobby.join("p2", host = false)
        assertEquals("还有 1 人没准备", lobby.start("p1"))

        lobby.setReady("p2", true)
        assertEquals("规则还没写，只给一句提示", "「花砖物语」的对局还在施工，先把人凑齐、座次排好", lobby.start("p1"))
        assertEquals("阶段还在大厅", LobbyPhase.Lobby, lobby.snapshot().phase)
    }
}
