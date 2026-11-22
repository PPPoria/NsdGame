package com.poria.netnsd.room

import com.poria.netnsd.link.Frame
import com.poria.netnsd.link.LinkCodec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `lobby` 频道路由格式单测：命令/事件往返、快照裁剪、坏负载不炸。
 *
 * 不碰 socket：`LobbyChannel` 的联机行为在 `LobbyOverLinkTest` 里。
 */
class LobbyProtocolTest {

    @Test
    fun `命令往返`() {
        fun roundTrip(command: LobbyProtocol.Command) =
            LobbyProtocol.decodeCommand(LobbyProtocol.encode(command))

        assertEquals(LobbyProtocol.Command.Join("小明"), roundTrip(LobbyProtocol.Command.Join("小明")))
        assertEquals(LobbyProtocol.Command.Say("你好"), roundTrip(LobbyProtocol.Command.Say("你好")))
        assertEquals(LobbyProtocol.Command.Ready(true), roundTrip(LobbyProtocol.Command.Ready(true)))
        assertEquals(LobbyProtocol.Command.TakeSeat(2), roundTrip(LobbyProtocol.Command.TakeSeat(2)))
        assertEquals(
            LobbyProtocol.Command.SwapSeats(0, 3),
            roundTrip(LobbyProtocol.Command.SwapSeats(0, 3)),
        )
        assertEquals(LobbyProtocol.Command.SetTableSize(3), roundTrip(LobbyProtocol.Command.SetTableSize(3)))
        assertEquals(LobbyProtocol.Command.SelectGame("azul"), roundTrip(LobbyProtocol.Command.SelectGame("azul")))
        assertEquals(
            LobbyProtocol.Command.SetOption("grey", true),
            roundTrip(LobbyProtocol.Command.SetOption("grey", true)),
        )
        assertEquals(LobbyProtocol.Command.Kick("p2"), roundTrip(LobbyProtocol.Command.Kick("p2")))
        assertEquals(
            LobbyProtocol.Command.SetAvatar("builtin:azul.grey"),
            roundTrip(LobbyProtocol.Command.SetAvatar("builtin:azul.grey")),
        )
        assertEquals(LobbyProtocol.Command.Start, roundTrip(LobbyProtocol.Command.Start))
    }

    @Test
    fun `事件往返，特殊字符交给 JSON`() {
        val snapshot = RoomSnapshot(
            roomName = "小明|的房间",
            hostId = "host-1",
            phase = RoomPhase.Lobby,
            gameId = "azul",
            tableSize = 4,
            startHint = "还差 1 人",
            options = linkedMapOf("grey" to true, "quick" to false),
            players = listOf(
                RoomPlayer("host-1", 0, "小明,老板", host = true, ready = false, avatar = "builtin:x"),
                RoomPlayer("p2", 2, "小红;同学", host = false, ready = true),
            ),
            messages = listOf(
                RoomMessage(1, "host-1", "开一局？"),
                RoomMessage(2, "p2", "a|b;c,d=e%f"),
            ),
        )

        val payload = LobbyProtocol.encode(LobbyProtocol.Event.Snapshot(snapshot))
        assertEquals(LobbyProtocol.Event.Snapshot(snapshot), LobbyProtocol.decodeEvent(payload))

        assertEquals(
            LobbyProtocol.Event.Rejected("只有房主能改设置"),
            LobbyProtocol.decodeEvent(LobbyProtocol.rejected("只有房主能改设置")),
        )
    }

    @Test
    fun `快照超预算时先丢最老的聊天，人和设置一条不少`() {
        val long = "砖".repeat(LobbyProtocol.MAX_TEXT)
        val messages = (1..40).map { RoomMessage(it, "p1", "第${it}条：$long") }
        val snapshot = RoomSnapshot(
            roomName = "长聊天",
            hostId = "p1",
            phase = RoomPhase.Lobby,
            gameId = "azul",
            tableSize = 4,
            startHint = "等 小明 准备",
            options = linkedMapOf("grey" to false, "quick" to false),
            players = listOf(RoomPlayer("p1", 0, "小明", host = true, ready = false)),
            messages = messages,
        )

        val payload = LobbyProtocol.encodeSnapshot(snapshot)
        val line = LinkCodec.encode(Frame(ns = LobbyProtocol.NS, p = payload))
        assertTrue(
            "整行不能超预算：${line.toByteArray(Charsets.UTF_8).size}",
            line.toByteArray(Charsets.UTF_8).size <= LobbyProtocol.MAX_SNAPSHOT_BYTES,
        )

        val decoded = (LobbyProtocol.decodeEvent(payload) as LobbyProtocol.Event.Snapshot).room
        assertTrue("必须丢掉一些老消息", decoded.messages.size < messages.size)
        assertEquals("最新的那条必须还在", messages.last(), decoded.messages.last())
        assertEquals("只丢头部，保留连续尾段", messages.takeLast(decoded.messages.size), decoded.messages)
        assertEquals("人和设置不受影响", snapshot.players, decoded.players)
        assertEquals(snapshot.options, decoded.options)
    }

    @Test
    fun `坏负载返回 null`() {
        listOf(null, JsonNull, JsonPrimitive("x"), JsonPrimitive(1)).forEach {
            assertNull("命令 `$it` 应该被忽略", LobbyProtocol.decodeCommand(it))
            assertNull("事件 `$it` 应该被忽略", LobbyProtocol.decodeEvent(it))
        }
    }
}
