package com.poria.netnsd.lobby

import com.poria.netnsd.link.LinkCodec
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `lobby` 频道的编解码：命令/事件往返、坏负载、快照超预算裁剪。 */
class LobbyProtocolTest {

    @Test
    fun `命令往返`() {
        val commands = listOf(
            LobbyProtocol.Command.Join,
            LobbyProtocol.Command.Say("开一局？"),
            LobbyProtocol.Command.Ready(true),
            LobbyProtocol.Command.TakeSeat(2),
            LobbyProtocol.Command.SwapSeats(0, 3),
            LobbyProtocol.Command.SetTableSize(3),
            LobbyProtocol.Command.SelectGame("azul"),
            LobbyProtocol.Command.SetOption("grey", true),
            LobbyProtocol.Command.Kick("p2"),
            LobbyProtocol.Command.Start,
        )
        commands.forEach { command ->
            assertEquals(command, LobbyProtocol.decodeCommand(LobbyProtocol.encode(command)))
        }
    }

    @Test
    fun `事件往返，特殊字符交给 JSON`() {
        val snapshot = LobbySnapshot(
            roomName = "小明的房间",
            hostId = "p1",
            phase = LobbyPhase.Lobby,
            gameId = "azul",
            tableSize = 4,
            options = mapOf("grey" to false),
            players = listOf(LobbyPlayer("p1", 0)),
            readyIds = listOf("p1"),
            messages = listOf(LobbyMessage(1, "p1", "带 | 竖线、\"引号\"、\\反斜杠 和 换行\n也算")),
        )
        val payload = LobbyProtocol.encode(LobbyProtocol.Event.Snapshot(snapshot))
        val back = LobbyProtocol.decodeEvent(payload)
        assertEquals(LobbyProtocol.Event.Snapshot(snapshot), back)

        assertEquals(
            LobbyProtocol.Event.Rejected("只有房主能改设置"),
            LobbyProtocol.decodeEvent(LobbyProtocol.rejected("  只有房主能改设置  ")),
        )
    }

    @Test
    fun `坏负载返回 null，不抛`() {
        assertNull(LobbyProtocol.decodeCommand(null))
        assertNull(LobbyProtocol.decodeEvent(null))
        assertNull("认不出的 type", LobbyProtocol.decodeCommand(json("{\"type\":\"???\"}")))
        assertNull("字段类型不对", LobbyProtocol.decodeCommand(json("{\"type\":\"seat\",\"seat\":\"zero\"}")))
    }

    @Test
    fun `快照超预算时先丢最老的聊天，人和设置一条不少`() {
        val chatty = LobbySnapshot(
            roomName = "小明的房间",
            hostId = "p1",
            phase = LobbyPhase.Lobby,
            gameId = "azul",
            tableSize = 4,
            options = mapOf("grey" to false),
            players = listOf(LobbyPlayer("p1", 0), LobbyPlayer("p2", 1)),
            readyIds = listOf("p2"),
            messages = (1..30).map { seq -> LobbyMessage(seq, "p1", "很长的消息".repeat(12) + seq) },
        )

        val payload = LobbyProtocol.encodeSnapshot(chatty)
        val event = LobbyProtocol.decodeEvent(payload) as LobbyProtocol.Event.Snapshot
        val line = LinkCodec.encode(com.poria.netnsd.link.Frame(ns = LobbyProtocol.NS, p = payload))

        assertTrue("裁剪后要在预算内：${line.toByteArray().size}", line.toByteArray().size <= LobbyProtocol.MAX_SNAPSHOT_BYTES)
        assertEquals("人一个不少", chatty.players, event.lobby.players)
        assertEquals("准备状态也在", chatty.readyIds, event.lobby.readyIds)
        assertEquals("设置也在", chatty.options, event.lobby.options)
        assertTrue("丢了最老的那几条", event.lobby.messages.size < chatty.messages.size)
        assertEquals("留下的还是最新的", chatty.messages.last(), event.lobby.messages.last())
    }

    private fun json(raw: String): JsonElement = LinkCodec.json.parseToJsonElement(raw)
}
