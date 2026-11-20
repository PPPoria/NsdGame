package com.poria.netnsd.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间规则单测：假的 `send` 就是"某个客户端收到的消息列表"，于是不需要任何 socket。
 *
 * 客户端收到的每条消息都是整份快照，所以断言直接对着快照做（`lines.last()` 解出来）。
 */
class RoomTest {

    /** 一个假客户端：收下的行全存起来，`close` 只是打个标记。 */
    private class Client(val id: String, val name: String, val host: Boolean) {
        val lines = mutableListOf<String>()
        var closed = false

        fun send(line: String): Boolean {
            lines += line
            return true
        }

        fun close() {
            closed = true
        }

        fun snapshots(): List<RoomSnapshot> = lines
            .filter { it.startsWith("SNAP|") }
            .map { (RoomProtocol.decodeEvent(it) as RoomEvent.Snapshot).snapshot }

        fun lastSnapshot(): RoomSnapshot = snapshots().last()
    }

    private val room = Room("测试房间")
    private val host = Client("host", "房主", host = true)
    private val guest = Client("p2", "客人", host = false)
    private val third = Client("p3", "老三", host = false)

    private fun join(client: Client): Room.JoinResult =
        room.join(client.id, client.name, client.host, client::send, client::close)

    // ── 进房 / 花名册 ───────────────────────────────────────────────────────

    @Test
    fun `进房立刻拿到快照，座位按顺序排，房主标记只给房主那条连接`() {
        assertEquals(Room.JoinResult.Joined, join(host))
        assertEquals(Room.JoinResult.Joined, join(guest))

        val snapshot = guest.lastSnapshot()
        assertEquals("房主", snapshot.host?.name)
        assertEquals("host", snapshot.hostId)
        assertEquals(listOf(0, 1), snapshot.players.map { it.seat })
        assertEquals(listOf("房主", "客人"), snapshot.players.map { it.name })
        assertEquals("azul", snapshot.gameId)
        assertEquals(4, snapshot.tableSize)
        assertEquals(RoomPhase.LOBBY, snapshot.phase)
        assertTrue("新人也是房主那条快照里的一员", snapshot.player("p2") != null)
        assertEquals("同一时刻所有人看到的快照必须一致", host.lastSnapshot(), guest.lastSnapshot())
    }

    @Test
    fun `重名、重复 id、空昵称都被拒绝`() {
        join(host)
        assertEquals(Room.JoinResult.DuplicateName, room.join("p9", "房主", false, { true }, {}))
        assertEquals(Room.JoinResult.AlreadyJoined, room.join("host", "别人", false, { true }, {}))
        assertEquals(Room.JoinResult.InvalidName, room.join("p9", "   ", false, { true }, {}))
        assertEquals(Room.JoinResult.InvalidId, room.join("  ", "小明", false, { true }, {}))
        assertEquals(1, room.size)
    }

    @Test
    fun `座位坐满后进不来`() {
        join(host)
        assertNull(room.setTableSize("host", 2))
        join(guest)
        assertEquals(Room.JoinResult.Full, room.join("p3", "老三", false, { true }, {}))
        assertEquals(2, room.size)
    }

    @Test
    fun `离开会广播新快照并释放座位`() {
        join(host)
        join(guest)

        room.leave("p2")
        assertEquals(listOf("房主"), host.lastSnapshot().players.map { it.name })
        assertEquals(1, room.size)

        val countAfterLeave = host.lines.size
        room.leave("p2") // 重复调用：不认识的座位直接忽略，不该再广播
        assertEquals(countAfterLeave, host.lines.size)

        assertEquals("空出来的座位可以被新来的人坐", Room.JoinResult.Joined, room.join("p4", "老四", false, { true }, {}))
        assertEquals(1, host.lastSnapshot().player("p4")!!.seat)
    }

    // ── 聊天 ────────────────────────────────────────────────────────────────

    @Test
    fun `说的话进快照，所有人拿到同一份`() {
        join(host)
        join(guest)

        assertNull(room.say("p2", "开一局？"))

        assertEquals("开一局？", host.lastSnapshot().messages.last().text)
        assertEquals("p2", host.lastSnapshot().messages.last().playerId)
        assertEquals(host.lastSnapshot(), guest.lastSnapshot())
    }

    @Test
    fun `空消息静默忽略，不在房间里的人发不了话`() {
        join(host)
        val before = host.lines.size

        assertNull("空消息不算错误，只是不发", room.say("host", "   "))
        assertEquals("不该广播", before, host.lines.size)

        assertNotNull("不在房间里的人要收到拒绝原因", room.say("nobody", "有人吗"))
    }

    // ── 座位 ────────────────────────────────────────────────────────────────

    @Test
    fun `自己只能坐空座位，调座次是房主的权限`() {
        join(host)
        join(guest)

        assertNull(room.takeSeat("p2", 3))
        assertEquals(3, guest.lastSnapshot().player("p2")!!.seat)

        assertNotNull("别人的座位坐不进去", room.takeSeat("p2", 0))
        assertNotNull("客人不能调座次", room.swapSeats("p2", 3, 1))

        assertNull("房主可以交换任意两个座位", room.swapSeats("host", 3, 0))
        assertEquals(0, guest.lastSnapshot().player("p2")!!.seat)
        assertEquals(3, guest.lastSnapshot().player("host")!!.seat)

        assertNull("一方是空位就是挪过去", room.swapSeats("host", 0, 2))
        assertEquals(2, guest.lastSnapshot().player("p2")!!.seat)

        assertNotNull("座位号超出牌桌要拒绝", room.swapSeats("host", 0, 9))
        assertNull("同一个座位什么都不用做", room.swapSeats("host", 2, 2))
    }

    @Test
    fun `改人数：不能少于在场人数，改小以后越界的人自动挪到空位`() {
        join(host)
        join(guest)
        room.takeSeat("p2", 3)

        assertNull(room.setTableSize("host", 2))
        val snapshot = guest.lastSnapshot()
        assertEquals(2, snapshot.tableSize)
        assertEquals("越界的 3 号位被挪到唯一空着的 1 号位", listOf(0, 1), snapshot.players.map { it.seat })

        assertNotNull("比在场人数还少不行", room.setTableSize("host", 1))
        assertNotNull("超过游戏上限不行", room.setTableSize("host", 5))
        assertNotNull("客人不能改", room.setTableSize("p2", 3))
    }

    // ── 准备 / 玩法 / 开局 ──────────────────────────────────────────────────

    @Test
    fun `准备状态决定 canStart，改玩法会清空准备`() {
        join(host)
        join(guest)
        assertFalse("有人没准备时不能开局", guest.lastSnapshot().canStart)

        assertNull(room.setReady("p2", true))
        assertTrue(guest.lastSnapshot().canStart)

        assertNull(room.setOption("host", "grey", true))
        val snapshot = guest.lastSnapshot()
        assertTrue(snapshot.options["grey"] == true)
        assertFalse("改玩法后所有人的准备清空", snapshot.canStart)
        assertNotNull("不存在的玩法要拒绝", room.setOption("host", "nope", true))
    }

    @Test
    fun `非房主改不了设置`() {
        join(host)
        join(guest)

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
        join(host)
        assertTrue("一个人开不了局", room.start("host")!!.contains("还差"))
        assertEquals("原因也进快照，客户端照抄就行", "还差 1 人", host.lastSnapshot().startHint)

        join(guest)
        assertTrue(room.start("host")!!.contains("准备"))
        assertTrue(host.lastSnapshot().startHint.contains("客人"))

        room.setReady("p2", true)
        assertEquals("都准备好了，快照里不再有阻拦原因", "", host.lastSnapshot().startHint)
        assertTrue("规则写好了才能真的开局", room.start("host")!!.contains("施工"))
        assertEquals("被挡下时房间状态不该动", RoomPhase.LOBBY, host.lastSnapshot().phase)
    }

    @Test
    fun `换游戏：同一个游戏是空操作，认不出的游戏要拒绝`() {
        join(host)
        val before = host.lines.size
        assertNull(room.selectGame("host", "azul"))
        assertEquals("选同一个游戏不该广播", before, host.lines.size)
        assertNotNull(room.selectGame("host", "nope"))
    }

    // ── 踢人 ────────────────────────────────────────────────────────────────

    @Test
    fun `踢人：被踢的人收到 BYE 并被关上连接，其余人拿到不含他的快照`() {
        join(host)
        join(guest)

        assertNull(room.kick("host", "p2"))

        assertTrue("被踢的连接要被关掉", guest.closed)
        assertTrue("最后一条是 BYE：${guest.lines.last()}", guest.lines.last().startsWith("BYE|"))
        assertEquals(listOf("房主"), host.lastSnapshot().players.map { it.name })
        assertEquals(1, room.size)

        assertNotNull("不能踢自己", room.kick("host", "host"))
        assertNotNull("踢一个已经不在的人要拒绝", room.kick("host", "p2"))
    }
}
