package com.poria.netnsd.room

import org.junit.Assert.assertArrayEquals
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
    fun `命令里的特殊字符交给 JSON，往返不会变形`() {
        // 这些字符以前要手写转义表挡（| ; , = %），现在由 JSON 负责
        val tricky = "a|b;c,d=e%f\"引号\"\\反斜杠"
        assertEquals(RoomCommand.Say(tricky), RoomProtocol.decodeCommand(RoomProtocol.say(tricky)))

        // 昵称有 12 字上限，所以这里用一个短的：重要的是竖线和引号不被信封切坏
        val name = "a|b\"引\\号"
        assertEquals(
            RoomCommand.Join("p1", name),
            RoomProtocol.decodeCommand(RoomProtocol.join("p1", name)),
        )
    }

    @Test
    fun `命令是 type 区分的 JSON`() {
        val line = RoomProtocol.say("你好")
        assertTrue("命令走 CMD 信封：$line", line.startsWith("CMD|{"))
        assertTrue("用 type 区分是哪个命令：$line", line.contains("\"type\":\"say\""))
        assertTrue("中文原样走 UTF-8，不转成 \\uXXXX：$line", line.contains("\"text\":\"你好\""))

        val start = RoomProtocol.start()
        assertEquals(RoomCommand.Start, RoomProtocol.decodeCommand(start))
        assertTrue(start.contains("\"type\":\"start\""))
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
            // 信封就不对（旧格式、缺竖线、负载为空）
            "", "|x", "NOPE|x", "JOIN", "JOIN|p1", "SAY|你好", "CMD", "CMD|", "SNAP|",
            // 信封对了但 JSON 坏 / 认不出这个命令
            "CMD|{", "CMD|{}", "CMD|{\"type\":\"nope\"}", "CMD|[1,2,3]", "CMD|\"文本\"",
        ).forEach {
            assertNull("命令 `$it` 应该被忽略", RoomProtocol.decodeCommand(it))
        }
        listOf(
            "", "|x", "NOPE|1", "SNAP", "SNAP|", "SNAP|{}", "SNAP|{bad json}",
            "ERROR", "ERROR|", "BYE|",
        ).forEach {
            assertNull("事件 `$it` 应该被忽略", RoomProtocol.decodeEvent(it))
        }
        // 旧协议的命令串现在一律不认（版本不一致时也别炸）
        assertNull(RoomProtocol.decodeCommand("JOIN|p1|小明"))
    }

    // ── 快照 ────────────────────────────────────────────────────────────────

    @Test
    fun `快照往返`() {
        val snapshot = RoomSnapshot(
            roomName = "小明|的房间",
            hostId = "host-1",
            phase = RoomPhase.Lobby,
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
        assertTrue("快照走 SNAP 信封：$line", line.startsWith("SNAP|{"))
        assertTrue("快照不能带换行", line.indexOf('\n') < 0)
        assertTrue("快照不能超预算", line.toByteArray(Charsets.UTF_8).size <= RoomProtocol.MAX_SNAPSHOT_BYTES)
        assertTrue("版本号要显式在线上：$line", line.contains("\"version\":${RoomProtocol.VERSION}"))
        assertTrue("阶段用枚举名：$line", line.contains("\"phase\":\"Lobby\""))

        val event = RoomProtocol.decodeEvent(line)
        assertNotNull("快照必须能解出来：$line", event)
        assertEquals(RoomEvent.Snapshot(snapshot), event)
    }

    @Test
    fun `空房间和空选项也能往返`() {
        val snapshot = RoomSnapshot(
            roomName = "空房间",
            hostId = "",
            phase = RoomPhase.Lobby,
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
            phase = RoomPhase.Lobby,
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
                roomName = "r", hostId = "", phase = RoomPhase.Lobby, gameId = "azul",
                tableSize = 4, startHint = "", options = emptyMap(), players = emptyList(),
                messages = emptyList(),
            )
        ).replace("\"version\":${RoomProtocol.VERSION}", "\"version\":99")
        assertTrue("确实改到了版本号", line.contains("\"version\":99"))
        assertNull("版本不一致要整份丢掉，别渲染半份状态", RoomProtocol.decodeEvent(line))
    }

    @Test
    fun `多出来的字段不会把老客户端炸掉`() {
        val line = RoomProtocol.snapshot(
            RoomSnapshot(
                roomName = "r", hostId = "", phase = RoomPhase.Lobby, gameId = "azul",
                tableSize = 4, startHint = "", options = emptyMap(), players = emptyList(),
                messages = emptyList(),
            )
        ).replace("\"roomName\"", "\"futureField\":123,\"roomName\"")
        // ignoreUnknownKeys：对端版本更新时照样能画
        assertNotNull(RoomProtocol.decodeEvent(line))
    }

    // ── 图片（头像 / 自定义纹理） ────────────────────────────────────────────

    @Test
    fun `要图和上传命令往返，二进制走 base64`() {
        val id = "a".repeat(40)
        assertEquals(RoomCommand.RequestBlob(id), RoomProtocol.decodeCommand(RoomProtocol.requestBlob(id)))

        val data = ByteArray(200) { it.toByte() }
        val decoded = RoomProtocol.decodeCommand(RoomProtocol.putBlob(id, 0, 3, data)) as RoomCommand.PutBlob
        assertEquals(id, decoded.id)
        assertEquals(0, decoded.index)
        assertEquals(3, decoded.total)
        assertEquals(RoomProtocol.ROLE_AVATAR, decoded.role)
        assertArrayEquals(data, decoded.data)
    }

    @Test
    fun `图片分块事件往返`() {
        val id = "b".repeat(40)
        val data = ByteArray(3_000) { (it % 256).toByte() }
        val line = RoomProtocol.blobChunk(id, 1, 2, data)
        assertTrue("分块走 BLOB 信封：$line", line.startsWith("BLOB|{"))

        val event = RoomProtocol.decodeEvent(line) as RoomEvent.Blob
        assertEquals(id, event.id)
        assertEquals(1, event.index)
        assertEquals(2, event.total)
        assertArrayEquals(data, event.data)

        val error = RoomProtocol.decodeEvent(RoomProtocol.blobError(id, "房主这里没有这张图"))
            as RoomEvent.BlobError
        assertEquals("房主这里没有这张图", error.reason)
    }

    @Test
    fun `非法分块被丢掉`() {
        val id = "c".repeat(40)
        assertNull("index 越界", RoomProtocol.decodeEvent(RoomProtocol.blobChunk(id, 5, 2, ByteArray(10))))
        assertNull(
            "一块不能超过 BLOB_CHUNK_BYTES",
            RoomProtocol.decodeEvent(
                RoomProtocol.blobChunk(id, 0, 1, ByteArray(RoomProtocol.BLOB_CHUNK_BYTES + 1)),
            ),
        )
        assertNull(
            "base64 坏了",
            RoomProtocol.decodeEvent("BLOB|{\"id\":\"$id\",\"index\":0,\"total\":1,\"data\":\"###\"}"),
        )
        assertNull(
            "没有 data 字段",
            RoomProtocol.decodeEvent("BLOB|{\"id\":\"$id\",\"index\":0,\"total\":1}"),
        )
    }
}
