package com.poria.netnsd.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议单测：编解码必须严格对称，坏包必须返回 `null`（不能抛、不能让谁的进程崩）。
 *
 * 这些类不碰 Android，所以 `./gradlew :app-net:test` 在 JVM 上秒跑。
 */
class RoomProtocolTest {

    // ── 命令 ────────────────────────────────────────────────────────────────

    @Test
    fun `命令往返`() {
        assertEquals(
            RoomCommand.Join("p1", "小明"),
            RoomProtocol.decodeCommand(RoomProtocol.join("p1", "  小明  ")),
        )
        assertEquals(RoomCommand.Say("你好"), RoomProtocol.decodeCommand(RoomProtocol.say("你好")))
        assertEquals(RoomCommand.Ready(true), RoomProtocol.decodeCommand(RoomProtocol.ready(true)))
        assertEquals(RoomCommand.Ready(false), RoomProtocol.decodeCommand(RoomProtocol.ready(false)))
        assertEquals(RoomCommand.TakeSeat(2), RoomProtocol.decodeCommand(RoomProtocol.takeSeat(2)))
        assertEquals(RoomCommand.SwapSeats(0, 3), RoomProtocol.decodeCommand(RoomProtocol.swapSeats(0, 3)))
        assertEquals(RoomCommand.SetTableSize(3), RoomProtocol.decodeCommand(RoomProtocol.setTableSize(3)))
        assertEquals(RoomCommand.SelectGame("azul"), RoomProtocol.decodeCommand(RoomProtocol.selectGame("azul")))
        assertEquals(
            RoomCommand.SetOption("grey", true),
            RoomProtocol.decodeCommand(RoomProtocol.setOption("grey", true)),
        )
        assertEquals(RoomCommand.Kick("p2"), RoomProtocol.decodeCommand(RoomProtocol.kick("p2")))
        assertEquals(RoomCommand.Start, RoomProtocol.decodeCommand(RoomProtocol.start()))
    }

    @Test
    fun `命令里的分隔符转义后还能原样解出来`() {
        val tricky = "a|b;c,d=e%f"
        assertEquals(RoomCommand.Say(tricky), RoomProtocol.decodeCommand(RoomProtocol.say(tricky)))
        assertEquals(RoomCommand.Kick(tricky), RoomProtocol.decodeCommand(RoomProtocol.kick(tricky)))
    }

    @Test
    fun `换行会被抹成空格`() {
        // 行协议根本不允许换行，这里挡的是调用方手滑
        assertEquals(
            RoomCommand.Say("上 下"),
            RoomProtocol.decodeCommand(RoomProtocol.say("上\n下")),
        )
        assertTrue(RoomProtocol.say("上\r下").indexOf('\n') < 0)
    }

    @Test
    fun `超长内容会被截断`() {
        val long = "字".repeat(RoomProtocol.MAX_TEXT + 50)
        val say = RoomProtocol.decodeCommand(RoomProtocol.say(long)) as RoomCommand.Say
        assertEquals(RoomProtocol.MAX_TEXT, say.text.length)

        val longName = "名".repeat(RoomProtocol.MAX_NAME + 5)
        val join = RoomProtocol.decodeCommand(RoomProtocol.join("p1", longName)) as RoomCommand.Join
        assertEquals(RoomProtocol.MAX_NAME, join.name.length)
    }

    @Test
    fun `坏包返回 null 而不是抛异常`() {
        listOf(
            "", "|x", "NOPE|x", "JOIN", "JOIN|", "JOIN|p1",
            "SEAT|x", "SWAP|1", "SWAP|1|x", "SIZE|x", "GAME|", "OPT|grey", "OPT||1",
            "KICK|", "READY|2",
        ).forEach {
            assertNull("命令 `$it` 应该被忽略", RoomProtocol.decodeCommand(it))
        }
        listOf("", "|x", "NOPE|1", "SNAP", "SNAP|", "SNAP|v=2").forEach {
            assertNull("事件 `$it` 应该被忽略", RoomProtocol.decodeEvent(it))
        }
        // 内容为空的 SAY / ERROR / BYE 是"合法但没用"：解出空串，交给房间层忽略
        assertEquals(RoomCommand.Say(""), RoomProtocol.decodeCommand("SAY"))
        assertEquals(RoomEvent.Error(""), RoomProtocol.decodeEvent("ERROR"))
        assertEquals(RoomEvent.Bye(""), RoomProtocol.decodeEvent("BYE"))
    }

    // ── 快照 ────────────────────────────────────────────────────────────────

    @Test
    fun `快照往返`() {
        val snapshot = RoomSnapshot(
            roomName = "小明|的房间",
            hostId = "host-1",
            phase = RoomPhase.LOBBY,
            gameId = "azul",
            tableSize = 4,
            startHint = "",
            options = linkedMapOf("grey" to true, "quick" to false),
            players = listOf(
                RoomPlayer("host-1", 0, "小明,老板", host = true, ready = false),
                RoomPlayer("p2", 2, "小红;同学", host = false, ready = true),
            ),
            messages = listOf(
                RoomMessage(1, "host-1", "开一局？"),
                RoomMessage(2, "p2", "a|b;c,d=e%f"),
            ),
        )

        val line = RoomProtocol.snapshot(snapshot)
        assertTrue("快照不能带换行", line.indexOf('\n') < 0)
        assertTrue("快照不能超预算", line.toByteArray(Charsets.UTF_8).size <= RoomProtocol.MAX_SNAPSHOT_BYTES)

        val event = RoomProtocol.decodeEvent(line)
        assertNotNull("快照必须能解出来：$line", event)
        assertEquals(RoomEvent.Snapshot(snapshot), event)
    }

    @Test
    fun `空房间和空选项也能往返`() {
        val snapshot = RoomSnapshot(
            roomName = "空房间",
            hostId = "",
            phase = RoomPhase.LOBBY,
            gameId = "azul",
            tableSize = 2,
            startHint = "还差 1 人",
            options = emptyMap(),
            players = emptyList(),
            messages = emptyList(),
        )
        assertEquals(RoomEvent.Snapshot(snapshot), RoomProtocol.decodeEvent(RoomProtocol.snapshot(snapshot)))
    }

    @Test
    fun `快照超预算时先丢最老的聊天，人和设置一条不少`() {
        val long = "砖".repeat(RoomProtocol.MAX_TEXT)
        val messages = (1..40).map { RoomMessage(it, "p1", "第${it}条：$long") }
        val snapshot = RoomSnapshot(
            roomName = "长聊天",
            hostId = "p1",
            phase = RoomPhase.LOBBY,
            gameId = "azul",
            tableSize = 4,
            startHint = "等 小明 准备",
            options = linkedMapOf("grey" to false, "quick" to false, "harsh" to false),
            players = listOf(RoomPlayer("p1", 0, "小明", host = true, ready = false)),
            messages = messages,
        )

        val line = RoomProtocol.snapshot(snapshot)
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= RoomProtocol.MAX_SNAPSHOT_BYTES)

        val decoded = (RoomProtocol.decodeEvent(line) as RoomEvent.Snapshot).snapshot
        assertTrue("必须丢掉一些老消息", decoded.messages.size < messages.size)
        assertEquals("最新的那条必须还在", messages.last(), decoded.messages.last())
        assertEquals("只丢头部，保留的是连续尾段", messages.takeLast(decoded.messages.size), decoded.messages)
        assertEquals("人和设置不受影响", snapshot.players, decoded.players)
        assertEquals(snapshot.options, decoded.options)
        assertEquals(snapshot.roomName, decoded.roomName)
        assertEquals(snapshot.tableSize, decoded.tableSize)
    }

    @Test
    fun `版本不认识就当坏包`() {
        val line = RoomProtocol.snapshot(
            RoomSnapshot(
                roomName = "r", hostId = "", phase = RoomPhase.LOBBY, gameId = "azul",
                tableSize = 4, startHint = "", options = emptyMap(), players = emptyList(),
                messages = emptyList(),
            )
        ).replace("|v=${RoomProtocol.VERSION}", "|v=99")
        assertNull("版本不一致要整份丢掉，别渲染半份状态", RoomProtocol.decodeEvent(line))
    }
}
