package com.poria.netnsd.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间规则单测：**纯状态机**，不碰 socket、不广播。
 *
 * 每个断言都对着 `room.snapshot()` 和 `room.version`：方法返回"没生效的原因"，
 * 改没改东西看版本号——Handler（`LobbyChannel`）就是靠这个决定要不要推快照。
 */
class RoomTest {

    private val room = Room("测试房间")

    private fun snap() = room.snapshot()

    private fun join(id: String, name: String, host: Boolean = false): Room.JoinResult =
        room.join(id, name, host)

    private fun joinHostAndGuest() {
        join("host", "房主", host = true)
        join("p2", "客人")
    }

    // ── 进房 / 花名册 ───────────────────────────────────────────────────────

    @Test
    fun `进房按最小空位排座，房主标记来自链路`() {
        assertEquals(Room.JoinResult.Joined, join("host", "房主", host = true))
        assertEquals(Room.JoinResult.Joined, join("p2", "客人"))

        val snapshot = snap()
        assertEquals("host", snapshot.hostId)
        assertEquals(listOf(0, 1), snapshot.players.map { it.seat })
        assertEquals(listOf("房主", "客人"), snapshot.players.map { it.name })
        assertTrue("房主标记来自链路，不是自己喊的", snapshot.players.first { it.id == "host" }.host)
        assertFalse(snapshot.players.first { it.id == "p2" }.host)
        assertEquals("azul", snapshot.gameId)
        assertEquals(4, snapshot.tableSize)
        assertEquals(RoomPhase.Lobby, snapshot.phase)
    }

    @Test
    fun `重名、重复 id、空昵称都被拒，而且什么都不改`() {
        join("host", "房主")
        val version = room.version

        assertEquals(Room.JoinResult.DuplicateName, join("p9", "房主"))
        assertEquals(Room.JoinResult.AlreadyJoined, join("host", "别人"))
        assertEquals(Room.JoinResult.InvalidName, join("p9", "   "))
        assertEquals(Room.JoinResult.InvalidId, join("  ", "小明"))
        assertEquals("被拒绝的不该影响状态", version, room.version)
        assertEquals(1, room.size)
    }

    @Test
    fun `座位满了进不来`() {
        join("host", "房主", host = true)
        assertNull(room.setTableSize("host", 2))
        join("p2", "客人")

        assertEquals(Room.JoinResult.Full, join("p3", "老三"))
        assertEquals(2, room.size)
    }

    @Test
    fun `离开会释放座位`() {
        joinHostAndGuest()

        assertTrue(room.leave("p2"))
        assertFalse("重复离开没有变化", room.leave("p2"))
        assertEquals(listOf("房主"), snap().players.map { it.name })

        assertEquals(Room.JoinResult.Joined, join("p4", "老四"))
        assertEquals("空出来的座位可以再坐", 1, snap().player("p4")!!.seat)
    }

    // ── 聊天 ────────────────────────────────────────────────────────────────

    @Test
    fun `说的话进快照，空消息不改版本`() {
        joinHostAndGuest()

        assertNull(room.say("p2", "开一局？"))
        assertEquals("开一局？", snap().messages.last().text)
        assertEquals("p2", snap().messages.last().playerId)

        val version = room.version
        assertNull("空消息静默忽略", room.say("host", "   "))
        assertEquals("不该改版本", version, room.version)

        assertNotNull("不在房间里的人发不了话", room.say("nobody", "有人吗"))
    }

    // ── 座位 ────────────────────────────────────────────────────────────────

    @Test
    fun `自己只能坐空座位，调座次是房主的权限`() {
        joinHostAndGuest()

        assertNull(room.takeSeat("p2", 3))
        assertEquals(3, snap().player("p2")!!.seat)

        assertNotNull("别人的座位坐不进去", room.takeSeat("p2", 0))
        assertNotNull("客人不能调座次", room.swapSeats("p2", 3, 1))

        assertNull("房主可以交换任意两个座位", room.swapSeats("host", 3, 0))
        assertEquals(0, snap().player("p2")!!.seat)
        assertEquals(3, snap().player("host")!!.seat)

        assertNull("一方是空位就是挪过去", room.swapSeats("host", 0, 2))
        assertEquals(2, snap().player("p2")!!.seat)

        assertNotNull("座位号超出牌桌要拒绝", room.swapSeats("host", 0, 9))
        assertNull("同一个座位什么都不用做", room.swapSeats("host", 2, 2))
    }

    @Test
    fun `改人数：不能少于在场人数，改小以后越界的人自动挪位`() {
        joinHostAndGuest()
        room.takeSeat("p2", 3)

        assertNull(room.setTableSize("host", 2))
        val snapshot = snap()
        assertEquals(2, snapshot.tableSize)
        assertEquals("越界的 3 号位被挪到唯一空着的 1 号位", listOf(0, 1), snapshot.players.map { it.seat })

        assertNotNull("比在场人数还少不行", room.setTableSize("host", 1))
        assertNotNull("超过游戏上限不行", room.setTableSize("host", 5))
        assertNotNull("客人不能改", room.setTableSize("p2", 3))
    }

    // ── 准备 / 玩法 / 开局 ──────────────────────────────────────────────────

    @Test
    fun `准备状态决定能不能开，改玩法会清空准备`() {
        joinHostAndGuest()
        assertFalse("有人没准备时不能开局", snap().canStart)

        assertNull(room.setReady("p2", true))
        assertTrue(snap().canStart)

        assertNull(room.setOption("host", "grey", true))
        val snapshot = snap()
        assertTrue(snapshot.options["grey"] == true)
        assertFalse("改玩法后所有人的准备清空", snapshot.canStart)
        assertNotNull("不存在的玩法要拒绝", room.setOption("host", "nope", true))
    }

    @Test
    fun `非房主改不了设置`() {
        joinHostAndGuest()

        listOf(
            room.swapSeats("p2", 0, 1),
            room.setTableSize("p2", 3),
            room.selectGame("p2", "azul"),
            room.setOption("p2", "grey", true),
            room.kick("p2", "host"),
            room.start("p2"),
        ).forEach { assertNotNull("客人不该有这个权限", it) }
    }

    @Test
    fun `房主按开始：人数不够、有人没准备、规则还没写`() {
        join("host", "房主", host = true)
        assertTrue("一个人开不了局", room.start("host")!!.contains("还差"))
        assertEquals("原因也进快照", "还差 1 人", snap().startHint)

        join("p2", "客人")
        assertTrue(room.start("host")!!.contains("准备"))
        assertTrue(snap().startHint.contains("客人"))

        room.setReady("p2", true)
        assertEquals("都准备好了，快照里不再有阻拦原因", "", snap().startHint)
        assertTrue("规则写好了才能真的开局", room.start("host")!!.contains("施工"))
    }

    @Test
    fun `换游戏：同一个游戏是空操作，认不出的游戏要拒绝`() {
        join("host", "房主", host = true)
        val version = room.version
        assertNull(room.selectGame("host", "azul"))
        assertEquals("选同一个游戏不该改版本", version, room.version)
        assertNotNull(room.selectGame("host", "nope"))
    }

    // ── 踢人 / 头像 ─────────────────────────────────────────────────────────

    @Test
    fun `踢人把人移出房间，非房主和踢自己都拒绝`() {
        joinHostAndGuest()

        assertNotNull("不能踢自己", room.kick("host", "host"))
        assertNotNull("客人不能踢人", room.kick("p2", "host"))

        val version = room.version
        assertNull(room.kick("host", "p2"))
        assertEquals("状态确实变了", version + 1, room.version)
        assertEquals(listOf("房主"), snap().players.map { it.name })

        assertNotNull("踢一个已经不在的人要拒绝", room.kick("host", "p2"))
    }

    @Test
    fun `头像 id 会进快照，非法 id 被拒`() {
        joinHostAndGuest()
        val id = "d".repeat(40)

        assertNull(room.setAvatar("p2", id))
        assertEquals("快照里只带 id，不带图片本体", id, snap().player("p2")!!.avatar)

        assertNull("打包素材的 builtin: id 也认", room.setAvatar("p2", "builtin:azul.grey"))
        assertEquals("builtin:azul.grey", snap().player("p2")!!.avatar)

        assertNotNull("不是 sha1 的 id 要拒", room.setAvatar("p2", "../../evil"))
        assertEquals("被拒的 id 不该改状态", "builtin:azul.grey", snap().player("p2")!!.avatar)
    }
}
