package com.poria.netnsd.azul

import com.poria.game.azul.AzulEngine
import com.poria.game.azul.AzulPhase
import com.poria.game.azul.Tile
import com.poria.netnsd.link.LinkCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `azul` 频道的负载编解码测试。
 *
 * 重点验两件事：
 * 1. **命令往返**（含 `Tile` 枚举——它在 `:game-core`，没标 `@Serializable`，靠 kotlinx 自带的枚举序列化）；
 * 2. **局面往返**（`AzulSnapshot` 在 `:game-core`、构造器是 `internal`，`:app-net` 只是用它的 serializer）。
 */
class AzulProtocolTest {

    @Test
    fun `命令往返`() {
        val commands = listOf(
            AzulProtocol.Command.Sync,
            AzulProtocol.Command.TakeFromPlate(
                row = 2,
                tile = Tile.Blue,
                plate = listOf(Tile.Blue, Tile.Blue, Tile.Red, Tile.Yellow),
            ),
            AzulProtocol.Command.TakeFromRedundant(row = 0, tile = Tile.FirstAct),
        )
        commands.forEach { command ->
            assertEquals(command, AzulProtocol.decodeCommand(AzulProtocol.encode(command)))
        }
    }

    @Test
    fun `枚举按名字上线，不依赖 entries 的顺序`() {
        val payload = AzulProtocol.encode(AzulProtocol.Command.TakeFromRedundant(1, Tile.White))
        assertTrue("帧里是名字：$payload", payload.toString().contains("\"White\""))
    }

    @Test
    fun `局面往返：game-core 的 AzulSnapshot 直接编解码`() {
        // 引擎刚建出来的那份空局面就是一份真实快照（内部构造器我们造不了，也没必要造）
        val snapshot = AzulEngine().state.value
        assertEquals(AzulPhase.Preparing, snapshot.phase)

        val payload = AzulProtocol.snapshot(snapshot)
        val back = AzulProtocol.decodeEvent(payload) as AzulProtocol.Event.Snapshot

        assertEquals(AzulProtocol.VERSION, back.version)
        assertEquals("整份局面一字不差", snapshot, back.game)
        assertEquals("该谁动也带上了", snapshot.actPlayer, back.game.actPlayer)
        assertEquals("玩家集合也带上了", snapshot.desktop.players, back.game.desktop.players)
        assertTrue(
            "4 人满台大概 2–3KB，别顶到 8KB：${LinkCodec.encode(com.poria.netnsd.link.Frame(ns = AzulProtocol.NS, p = payload)).length}",
            LinkCodec.encode(com.poria.netnsd.link.Frame(ns = AzulProtocol.NS, p = payload)).length < AzulProtocol.MAX_SNAPSHOT_BYTES,
        )
    }

    @Test
    fun `坏负载返回 null，不抛`() {
        assertNull(AzulProtocol.decodeCommand(null))
        assertNull(AzulProtocol.decodeEvent(null))
        assertNull(
            "认不出的 type",
            AzulProtocol.decodeCommand(LinkCodec.json.parseToJsonElement("{\"type\":\"???\"}")),
        )
        assertNull(
            "枚举名不认识",
            AzulProtocol.decodeCommand(
                LinkCodec.json.parseToJsonElement("{\"type\":\"redundant\",\"row\":0,\"tile\":\"Pink\"}"),
            ),
        )
    }

    @Test
    fun `拒绝原因会截断，不会撑爆一行`() {
        val payload = AzulProtocol.rejected("很长的原因".repeat(100))
        val reason = (AzulProtocol.decodeEvent(payload) as AzulProtocol.Event.Rejected).reason
        assertTrue("截到 80 字以内：${reason.length}", reason.length <= 80)
    }
}
