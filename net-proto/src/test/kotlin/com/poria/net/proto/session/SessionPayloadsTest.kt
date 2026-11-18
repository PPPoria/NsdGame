package com.poria.net.proto.session

import com.poria.net.proto.Envelope
import com.poria.net.proto.MsgType
import com.poria.net.proto.ProtocolJson
import com.poria.net.proto.ProtocolVersion
import com.poria.net.proto.room.HostAddress
import com.poria.net.proto.room.RoomDescriptor
import com.poria.net.proto.room.RoomPhase
import com.poria.net.proto.room.RoomPlayer
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话载荷的线缆契约测试：**每一屏都在钉 `PROTOCOL.md` §2 的字段表**。
 *
 * 归属：传输与消息协议。
 *
 * 分两类断言：
 * 1. **round-trip**：`载荷 → Envelope → 整帧 → readFrame → decodePayload` 回到同一个值；
 * 2. **线格式**：直接看 JSON 文本。这一类才是 `PROTOCOL.md` 的守门员——
 *    一个不读 Kotlin 的实现者只靠那份文档互通，所以字段名、枚举字面量必须被测试钉死。
 */
class SessionPayloadsTest {

    private val codec = SessionPayloads.codec()

    private val descriptor = RoomDescriptor(
        protoVersion = ProtocolVersion.CURRENT,
        gameId = "gomoku",
        gameVersion = "1.2.0",
        roomInstanceId = "b1f0-uuid",
        roomName = "张三的房间",
        hostNick = "张三",
        playerCount = 3,
        maxPlayers = 6,
        phase = RoomPhase.LOBBY,
        hasPasscode = true,
        addresses = listOf(HostAddress("192.168.1.7", 41234)),
    )

    private val players = listOf(
        RoomPlayer(seat = 0, playerId = "p-0", nick = "张三", host = true),
        RoomPlayer(seat = 1, playerId = "p-1", nick = "李四"),
        RoomPlayer(seat = 2, playerId = "p-2", nick = "王五", online = false),
    )

    // ───────────────────────── round-trip ─────────────────────────

    @Test
    fun `HELLO 载荷往返一致`() = runTest {
        roundTrip(
            MsgType.HELLO,
            Hello("gomoku", "1.2.0", "p-1", "李四", "Pixel 8"),
            Hello.serializer(),
        )
    }

    @Test
    fun `WELCOME 载荷往返一致且自动去掉地址候选`() = runTest {
        val welcome = Welcome.of(descriptor, sessionId = "s-42")
        assertEquals(emptyList<HostAddress>(), welcome.room.addresses)
        roundTrip(MsgType.WELCOME, welcome, Welcome.serializer())
    }

    @Test
    fun `REJECT 载荷往返一致（五个拒绝码全覆盖）`() = runTest {
        RejectCode.entries.forEach { rejectCode ->
            roundTrip(
                MsgType.REJECT,
                Reject(rejectCode, detail = "diag: $rejectCode"),
                Reject.serializer(),
            )
        }
    }

    @Test
    fun `JOIN_REQ 载荷往返一致（含无口令房间）`() = runTest {
        roundTrip(MsgType.JOIN_REQ, JoinReq("1234", "李四"), JoinReq.serializer())
        roundTrip(MsgType.JOIN_REQ, JoinReq(null, "李四"), JoinReq.serializer())
    }

    @Test
    fun `JOIN_OK 载荷往返一致（ADR-018：只有私有字段）`() = runTest {
        roundTrip(MsgType.JOIN_OK, JoinOk(sessionToken = "tok-abc", mySeat = 1), JoinOk.serializer())
    }

    @Test
    fun `LOBBY_REQ 载荷往返一致（换座与非换座两种形态）`() = runTest {
        roundTrip(
            MsgType.LOBBY_REQ,
            LobbyReq(reqSeq = 7, op = LobbyOp.CHANGE_SEAT, seat = 0),
            LobbyReq.serializer(),
        )
        roundTrip(
            MsgType.LOBBY_REQ,
            LobbyReq(reqSeq = 8, op = LobbyOp.LEAVE),
            LobbyReq.serializer(),
        )
    }

    @Test
    fun `LOBBY_RESULT 载荷往返一致（软拒绝用码而不是文案）`() = runTest {
        roundTrip(
            MsgType.LOBBY_RESULT,
            LobbyResult(
                reqSeq = 7,
                op = LobbyOp.CHANGE_SEAT,
                ok = false,
                code = LobbyRejectCode.SEAT_OCCUPIED,
                detail = "seat 0 held by p-0", // diagnostic-only
            ),
            LobbyResult.serializer(),
        )
        roundTrip(
            MsgType.LOBBY_RESULT,
            LobbyResult(reqSeq = 8, op = LobbyOp.LEAVE, ok = true),
            LobbyResult.serializer(),
        )
    }

    @Test
    fun `RESUME_REQ 载荷往返一致`() = runTest {
        roundTrip(MsgType.RESUME_REQ, ResumeReq("tok-abc", 41), ResumeReq.serializer())
    }

    @Test
    fun `KICK BYE ERROR 载荷往返一致`() = runTest {
        roundTrip(MsgType.KICK, Kick(KickReason.BY_HOST, detail = "kicked by host"), Kick.serializer())
        roundTrip(MsgType.BYE, Bye(ByeReason.HOST_DISSOLVED, detail = "room closed"), Bye.serializer())
        roundTrip(MsgType.ERROR, ProtocolError("UNEXPECTED_MESSAGE", "握手期收到 CMD"), ProtocolError.serializer())
    }

    @Test
    fun `PING 与 PONG 没有载荷：decodePayload 返回 JsonNull`() = runTest {
        for (type in listOf(MsgType.PING, MsgType.PONG)) {
            val frame = codec.encode(Envelope.of(type, JsonNull))
            val decoded = codec.readFrame(ByteArrayInputStream(frame))
            assertEquals(type, decoded.type)
            assertEquals("$type 不应带字段", JsonNull, decoded.payload)
            assertEquals(JsonNull, codec.decodePayload(decoded))
        }
    }

    // ───────────────────────── 线格式（PROTOCOL.md 守门） ─────────────────────────

    @Test
    fun `HELLO 线缆上不含 protoVersion（帧头已有，不重复）`() {
        val json = jsonOf(MsgType.HELLO, Hello("gomoku", "1.2.0", "p-1", "李四", "jvm"), Hello.serializer())

        assertFalse("HELLO 不得重复 protoVersion：$json", json.contains("protoVersion"))
        listOf("gameId", "gameVersion", "playerId", "nick", "deviceModel").forEach { field ->
            assertTrue("HELLO 缺少字段 $field：$json", json.contains("\"$field\""))
        }
    }

    @Test
    fun `房间阶段在线缆上是 wireValue 而不是 Kotlin 常量名`() {
        val json = jsonOf(MsgType.WELCOME, Welcome.of(descriptor, "s-1"), Welcome.serializer())

        assertTrue("阶段应序列化为 \"Lobby\"：$json", json.contains("\"phase\":\"Lobby\""))
        assertFalse("阶段不得序列化为 LOBBY：$json", json.contains("\"phase\":\"LOBBY\""))
        // withoutAddresses() 之后候选列表为空（encodeDefaults=true，因此字段仍在、值为空数组）
        assertTrue("WELCOME 的地址候选必须为空：$json", json.contains("\"addresses\":[]"))
    }

    @Test
    fun `JOIN_OK 不得携带名册与房间（权威状态只来自 SNAPSHOT）`() {
        val json = jsonOf(MsgType.JOIN_OK, JoinOk("tok", 1), JoinOk.serializer())

        listOf("sessionToken", "mySeat").forEach { field ->
            assertTrue("JOIN_OK 缺少私有字段 $field：$json", json.contains("\"$field\""))
        }
        listOf("players", "room", "nick", "playerId").forEach { field ->
            assertFalse("JOIN_OK 不得携带持续状态字段 $field（ADR-018）：$json", json.contains("\"$field\""))
        }
    }

    @Test
    fun `名册是 RoomPlayer 的扁平结构（裸 Int 座位号，没有 Seat 类型）`() {
        // ADR-018 之后名册只在 SNAPSHOT 里出现（SNAPSHOT 载荷归游戏协议设计师），
        // 这里只钉线缆投影本身的形状，保证 PROTOCOL.md §2.4 的样例是准的。
        val json = ProtocolJson.instance.encodeToString(RoomPlayer.serializer(), players[2])

        listOf("seat", "playerId", "nick", "host", "online").forEach { field ->
            assertTrue("RoomPlayer 缺少字段 $field：$json", json.contains("\"$field\""))
        }
        assertTrue("保座中的玩家应在线缆上表现为 online=false：$json", json.contains("\"online\":false"))
        assertTrue("座位号必须是裸 Int：$json", json.contains("\"seat\":2"))
    }

    @Test
    fun `LOBBY 的类型码与线缆字面量`() {
        assertEquals(17, MsgType.LOBBY_REQ.code)
        assertEquals(18, MsgType.LOBBY_RESULT.code)
        assertEquals(MsgType.LOBBY_REQ, MsgType.fromCode(17))
        assertEquals(MsgType.LOBBY_RESULT, MsgType.fromCode(18))

        val req = jsonOf(MsgType.LOBBY_REQ, LobbyReq(1, LobbyOp.CHANGE_SEAT, 0), LobbyReq.serializer())
        assertTrue("换座应序列化为 \"ChangeSeat\"：$req", req.contains("\"op\":\"ChangeSeat\""))
        assertTrue("请求序号应上线：$req", req.contains("\"reqSeq\":1"))

        val leave = jsonOf(MsgType.LOBBY_REQ, LobbyReq(2, LobbyOp.LEAVE), LobbyReq.serializer())
        assertTrue("退出应序列化为 \"Leave\"：$leave", leave.contains("\"op\":\"Leave\""))
        assertFalse("Leave 不应带座位号：$leave", leave.contains("\"seat\""))

        val result = jsonOf(
            MsgType.LOBBY_RESULT,
            LobbyResult(1, LobbyOp.CHANGE_SEAT, ok = false, code = LobbyRejectCode.SEAT_OCCUPIED),
            LobbyResult.serializer(),
        )
        assertTrue("软拒绝应带 ok=false：$result", result.contains("\"ok\":false"))
        assertTrue(
            "软拒绝必须用码而不是文案：$result",
            result.contains("\"code\":\"SEAT_OCCUPIED\""),
        )
        assertFalse("软拒绝不得用可读文案当契约（ADR-020）：$result", result.contains("\"reason\""))
    }

    @Test
    fun `协议版本是 2：LOBBY 类型属于 v2 的线格式`() = runTest {
        assertEquals("ADR-019 改了类型表，必须升版本", 2, ProtocolVersion.CURRENT)

        val frame = codec.encode(
            Envelope.of(
                MsgType.LOBBY_REQ,
                ProtocolJson.instance.encodeToJsonElement(
                    LobbyReq.serializer(),
                    LobbyReq(1, LobbyOp.LEAVE),
                ),
            ),
        )
        assertEquals("帧头 proto 字节应为 2", 2, frame[2].toInt())
        assertEquals(
            MsgType.LOBBY_REQ,
            codec.readFrame(ByteArrayInputStream(frame)).type,
        )
    }

    @Test
    fun `驱动 UI 的字段必须是枚举，纯诊断字段才允许自由字符串（ADR-020）`() {
        val reject = jsonOf(
            MsgType.REJECT,
            Reject(RejectCode.PASSCODE_WRONG, detail = "diag only"),
            Reject.serializer(),
        )
        assertTrue("拒绝码必须是枚举字面量：$reject", reject.contains("\"code\":\"PASSCODE_WRONG\""))
        assertTrue("诊断补充字段名为 detail：$reject", reject.contains("\"detail\""))

        val kick = jsonOf(MsgType.KICK, Kick(KickReason.BY_HOST), Kick.serializer())
        assertTrue("踢人原因是枚举：$kick", kick.contains("\"code\":\"BY_HOST\""))

        val bye = jsonOf(MsgType.BYE, Bye(ByeReason.HOST_EXIT), Bye.serializer())
        assertTrue("关房原因是枚举：$bye", bye.contains("\"code\":\"HOST_EXIT\""))

        val lobby = jsonOf(
            MsgType.LOBBY_RESULT,
            LobbyResult(1, LobbyOp.CHANGE_SEAT, ok = false, code = LobbyRejectCode.NOT_IN_LOBBY),
            LobbyResult.serializer(),
        )
        assertTrue("大厅拒绝码是枚举：$lobby", lobby.contains("\"code\":\"NOT_IN_LOBBY\""))

        listOf(reject, kick, bye, lobby).forEach { json ->
            assertFalse(
                "ADR-020：驱动 UI 的字段必须是枚举，不得用 reason 承载文案：$json",
                json.contains("\"reason\""),
            )
        }

        // ERROR 是例外：开放字符串不驱动任何 UI 分支，只进日志（ADR-020 明确保留）
        val error = jsonOf(
            MsgType.ERROR,
            ProtocolError("UNEXPECTED_MESSAGE", "free text is fine here"),
            ProtocolError.serializer(),
        )
        assertTrue("ERROR 的开放字符串必须保留：$error", error.contains("\"code\":\"UNEXPECTED_MESSAGE\""))
    }

    @Test
    fun `LobbyRejectCode 四个码逐字上线`() {
        LobbyRejectCode.entries.forEach { code ->
            val json = jsonOf(
                MsgType.LOBBY_RESULT,
                LobbyResult(1, LobbyOp.CHANGE_SEAT, ok = false, code = code),
                LobbyResult.serializer(),
            )
            assertTrue("拒绝码应逐字序列化：$json", json.contains("\"code\":\"${code.name}\""))
        }
    }

    @Test
    fun `WELCOME 载荷的键就是 room 与 sessionId（多一个都不行）`() {
        assertEquals(
            "ADR-018：WELCOME 只承载加入前展示所需的两项",
            setOf("room", "sessionId"),
            payloadKeysOf(MsgType.WELCOME, Welcome.of(descriptor, "s-1"), Welcome.serializer()),
        )
    }

    @Test
    fun `JOIN_OK 载荷的键就是 sessionToken 与 mySeat（多一个都不行）`() {
        assertEquals(
            "ADR-018：JOIN_OK 只留两个私有字段",
            setOf("sessionToken", "mySeat"),
            payloadKeysOf(MsgType.JOIN_OK, JoinOk("tok", 1), JoinOk.serializer()),
        )
    }

    @Test
    fun `REJECT 的码是枚举名逐字对应`() {
        RejectCode.entries.forEach { code ->
            val json = jsonOf(MsgType.REJECT, Reject(code, null), Reject.serializer())
            assertTrue("拒绝码应逐字序列化：$json", json.contains("\"code\":\"${code.name}\""))
        }
    }

    @Test
    fun `会话信封的 seq 与 cmdSeq 默认 0 仍写出（抓包可读）`() = runTest {
        val frame = codec.encode(
            Envelope.of(
                MsgType.JOIN_OK,
                ProtocolJson.instance.encodeToJsonElement(
                    JoinOk.serializer(),
                    JoinOk(sessionToken = "tok", mySeat = 0),
                ),
            ),
        )
        val decoded = codec.readFrame(ByteArrayInputStream(frame))

        // EnvelopeBody 会写出 seq/cmdSeq；帧头里则是 type/proto
        val body = String(frame, FrameBody.UTF8_OFFSET, frame.size - FrameBody.UTF8_OFFSET, Charsets.UTF_8)
        assertTrue("帧体应含 seq 字段：$body", body.contains("\"seq\":0"))
        assertTrue("帧体应含 cmdSeq 字段：$body", body.contains("\"cmdSeq\":0"))
        assertEquals(0L, decoded.seq)
        assertEquals(0L, decoded.cmdSeq)
    }

    @Test
    fun `未注册的游戏载荷仍能安全穿过帧层（返回 JsonElement）`() = runTest {
        val payload = JsonPrimitive("不透明")
        val frame = codec.encode(Envelope.of(MsgType.CMD, payload))
        val decoded = codec.readFrame(ByteArrayInputStream(frame))

        assertEquals(MsgType.CMD, decoded.type)
        assertEquals(payload, codec.decodePayload(decoded))
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private suspend fun <T : Any> roundTrip(
        type: MsgType,
        value: T,
        serializer: KSerializer<T>,
    ) {
        val env = Envelope.of(type, ProtocolJson.instance.encodeToJsonElement(serializer, value))
        val frame = codec.encode(env)

        val decoded = codec.readFrame(ByteArrayInputStream(frame))
        assertEquals(type, decoded.type)
        assertEquals(ProtocolVersion.CURRENT, decoded.protoVersion)
        assertEquals(value, codec.decodePayload(decoded))
    }

    private fun <T : Any> jsonOf(type: MsgType, value: T, serializer: KSerializer<T>): String =
        codec.encode(Envelope.of(type, ProtocolJson.instance.encodeToJsonElement(serializer, value)))
            .let { String(it, FrameBody.UTF8_OFFSET, it.size - FrameBody.UTF8_OFFSET, Charsets.UTF_8) }

    /** 帧体 JSON 里 `payload` 对象的键集合——用于"多一个字段都不行"这类防回归断言。 */
    private fun <T : Any> payloadKeysOf(type: MsgType, value: T, serializer: KSerializer<T>): Set<String> =
        ProtocolJson.instance
            .parseToJsonElement(jsonOf(type, value, serializer))
            .jsonObject["payload"]!!
            .jsonObject
            .keys

    /** 帧头 9 字节之后的帧体起点。 */
    private object FrameBody {
        const val UTF8_OFFSET: Int = com.poria.net.proto.frame.FrameHeader.SIZE
    }
}
