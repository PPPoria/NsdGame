package com.poria.net.proto.frame

import com.poria.net.proto.Envelope
import com.poria.net.proto.MsgType
import com.poria.net.proto.ProtocolVersion
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `:net-proto` 帧层验收测试（对应 ARCHITECTURE.md §8 M0 验收标准 ①②③④ 与 §9.2）。
 *
 * 归属：传输与消息协议。全部纯 JVM，不碰 socket，目标 < 2s。
 */
class FrameCodecTest {

    private val codec = JsonFrameCodec()

    // ─────────────────────────── ② round-trip ───────────────────────────

    @Test
    fun `任意长度载荷 round-trip 完全一致`() = runTest {
        val sizes = intArrayOf(0, 1, 9, 64, 1024, 64 * 1024, 200 * 1024)
        for (size in sizes) {
            val env = Envelope(
                type = MsgType.EVENT_BATCH,
                protoVersion = ProtocolVersion.CURRENT,
                payload = JsonPrimitive("x".repeat(size)),
                seq = 41,
                cmdSeq = 7,
            )
            val frame = codec.encode(env)
            val decoded = codec.readFrame(ByteArrayInputStream(frame))

            assertEquals("载荷长度 $size 时 Envelope 不一致", env, decoded)
            assertArrayEquals("载荷长度 $size 时字节不完全一致", frame, codec.encode(decoded))
        }
    }

    @Test
    fun `seq 与 cmdSeq 与 protoVersion 原样保留`() = runTest {
        val env = Envelope(
            type = MsgType.CMD,
            protoVersion = ProtocolVersion.CURRENT,
            payload = JsonPrimitive("落子"),
            seq = Long.MAX_VALUE,
            cmdSeq = 12345,
        )
        val decoded = codec.readFrame(ByteArrayInputStream(codec.encode(env)))

        assertEquals(Long.MAX_VALUE, decoded.seq)
        assertEquals(12345L, decoded.cmdSeq)
        assertEquals(ProtocolVersion.CURRENT, decoded.protoVersion)
        assertEquals(MsgType.CMD, decoded.type)
    }

    // ──────────────── ② 暴力切分：每个偏移量都当一次半包 ────────────────

    @Test
    fun `在每一个偏移量处切分都能解析出同一结果（半包）`() = runTest {
        val env = Envelope(
            type = MsgType.CMD,
            protoVersion = ProtocolVersion.CURRENT,
            payload = JsonPrimitive("落子到 (7,7)"),
            seq = 3,
            cmdSeq = 3,
        )
        val frame = codec.encode(env)

        for (maxChunk in intArrayOf(1, 5, 9)) {
            for (cut in 0..frame.size) {
                // 每次读取都不跨过 cut 点：等价于"把一条消息切成两段喂进去"
                val stream = SplitStream(frame, cut, maxChunk)
                val decoded = codec.readFrame(stream)
                assertEquals("cut=$cut maxChunk=$maxChunk 解析结果不一致", env, decoded)
            }
        }
    }

    @Test
    fun `一次只读 1 字节也不能丢半包`() = runTest {
        val env = Envelope(
            type = MsgType.SNAPSHOT,
            protoVersion = ProtocolVersion.CURRENT,
            payload = JsonPrimitive("y".repeat(4096)),
        )
        val frame = codec.encode(env)
        val decoded = codec.readFrame(SplitStream(frame, cut = frame.size, maxChunk = 1))
        assertEquals(env, decoded)
    }

    @Test
    fun `粘包：连续 100 帧可以逐帧读出且顺序正确`() = runTest {
        val frames = (0 until 100).map { i ->
            codec.encode(
                Envelope(
                    type = MsgType.CMD,
                    protoVersion = ProtocolVersion.CURRENT,
                    payload = JsonPrimitive("cmd-$i"),
                    cmdSeq = i.toLong(),
                ),
            )
        }
        val sticky = frames.fold(ByteArray(0)) { acc, f -> acc + f }
        val input = SplitStream(sticky, cut = sticky.size, maxChunk = 7)

        for (i in 0 until 100) {
            val decoded = codec.readFrame(input)
            assertEquals(MsgType.CMD, decoded.type)
            assertEquals("cmd-$i", (decoded.payload as JsonPrimitive).content)
            assertEquals(i.toLong(), decoded.cmdSeq)
        }
        // 粘包读完之后必须是干净 EOF，而不是截断
        val eof = runCatching { codec.readFrame(input) }.exceptionOrNull()
        assertTrue("期望 EOFException，实际 $eof", eof is EOFException)
    }

    // ─────────────────── ④ 表头 1 字节错乱立即拒绝 ───────────────────

    @Test
    fun `magic 错乱立即拒绝`() = runTest {
        val frame = codec.encode(Envelope.of(MsgType.PING)).also { it[0] = 0x00 }
        val error = expectProtocolError { codec.readFrame(ByteArrayInputStream(frame)) }
        assertEquals(FrameProtocolException.Kind.BAD_MAGIC, error.kind)
    }

    @Test
    fun `未知消息类型码被拒绝`() = runTest {
        val frame = codec.encode(Envelope.of(MsgType.PING)).also { it[3] = 0x7F }
        val error = expectProtocolError { codec.readFrame(ByteArrayInputStream(frame)) }
        assertEquals(FrameProtocolException.Kind.BAD_TYPE, error.kind)
    }

    @Test
    fun `未定义的 flags 被拒绝`() = runTest {
        val frame = codec.encode(Envelope.of(MsgType.PING)).also { it[4] = 0x01 }
        val error = expectProtocolError { codec.readFrame(ByteArrayInputStream(frame)) }
        assertEquals(FrameProtocolException.Kind.UNSUPPORTED_FLAGS, error.kind)
    }

    // ───────────────────── ③ 帧长上限与截断 ─────────────────────

    @Test
    fun `载荷恰好等于上限时可以编解码`() = runTest {
        val overhead = codec.encode(Envelope.of(MsgType.SNAPSHOT, JsonPrimitive("")))
            .size - FrameHeader.SIZE
        val fit = FrameHeader.MAX_PAYLOAD_BYTES - overhead

        val env = Envelope.of(MsgType.SNAPSHOT, JsonPrimitive("x".repeat(fit)))
        val frame = codec.encode(env)
        assertEquals(FrameHeader.SIZE + FrameHeader.MAX_PAYLOAD_BYTES, frame.size)
        assertEquals(env, codec.readFrame(ByteArrayInputStream(frame)))
    }

    @Test
    fun `载荷超过上限在编码时就失败`() {
        val overhead = codec.encode(Envelope.of(MsgType.SNAPSHOT, JsonPrimitive("")))
            .size - FrameHeader.SIZE
        val tooBig = Envelope.of(
            MsgType.SNAPSHOT,
            JsonPrimitive("x".repeat(FrameHeader.MAX_PAYLOAD_BYTES - overhead + 1)),
        )

        try {
            codec.encode(tooBig)
            fail("期望 encode 抛 PAYLOAD_TOO_LARGE")
        } catch (e: FrameProtocolException) {
            assertEquals(FrameProtocolException.Kind.PAYLOAD_TOO_LARGE, e.kind)
        }
    }

    @Test
    fun `声明超长载荷时不读载荷直接拒绝`() = runTest {
        val header = FrameHeader(
            protoVersion = ProtocolVersion.CURRENT,
            typeCode = MsgType.SNAPSHOT.code,
            flags = FrameHeader.FLAGS_NONE,
            payloadLength = FrameHeader.MAX_PAYLOAD_BYTES + 1,
        ).toByteArray()

        val error = expectProtocolError { codec.readFrame(ByteArrayInputStream(header)) }
        assertEquals(FrameProtocolException.Kind.PAYLOAD_TOO_LARGE, error.kind)
    }

    @Test
    fun `长度字段高位被填满时按超限拒绝`() = runTest {
        val header = FrameHeader(
            protoVersion = ProtocolVersion.CURRENT,
            typeCode = MsgType.SNAPSHOT.code,
            flags = FrameHeader.FLAGS_NONE,
            payloadLength = -1, // 0xFFFFFFFF：所有位都是 1
        ).toByteArray()

        val error = expectProtocolError { codec.readFrame(ByteArrayInputStream(header)) }
        assertEquals(FrameProtocolException.Kind.PAYLOAD_TOO_LARGE, error.kind)
    }

    @Test
    fun `帧头只读到一半就 EOF 被判为截断`() = runTest {
        val frame = codec.encode(Envelope.of(MsgType.PING))
        val error = expectProtocolError {
            codec.readFrame(ByteArrayInputStream(frame.copyOfRange(0, 5)))
        }
        assertEquals(FrameProtocolException.Kind.TRUNCATED_HEADER, error.kind)
    }

    @Test
    fun `载荷只读到一半就 EOF 被判为截断`() = runTest {
        val frame = codec.encode(Envelope.of(MsgType.CMD, JsonPrimitive("a".repeat(100))))
        val error = expectProtocolError {
            codec.readFrame(ByteArrayInputStream(frame.copyOfRange(0, frame.size - 10)))
        }
        assertEquals(FrameProtocolException.Kind.TRUNCATED_PAYLOAD, error.kind)
    }

    @Test
    fun `帧边界上的 EOF 是正常结束而非协议错误`() = runTest {
        val input = ByteArrayInputStream(ByteArray(0))
        val error = runCatching { codec.readFrame(input) }.exceptionOrNull()
        assertTrue("期望 EOFException，实际 $error", error is EOFException)
    }

    @Test
    fun `非法 JSON 载荷被拒绝`() = runTest {
        val body = "{not json}"
            .toByteArray(Charsets.UTF_8)
        val frame = FrameIo.frameOf(
            FrameHeader(ProtocolVersion.CURRENT, MsgType.CMD.code, FrameHeader.FLAGS_NONE, body.size),
            body,
        )
        val error = expectProtocolError { codec.readFrame(ByteArrayInputStream(frame)) }
        assertEquals(FrameProtocolException.Kind.MALFORMED_JSON, error.kind)
    }

    @Test
    fun `缺少 payload 字段被拒绝`() = runTest {
        val body = """{"seq":1}""".toByteArray(Charsets.UTF_8)
        val frame = FrameIo.frameOf(
            FrameHeader(ProtocolVersion.CURRENT, MsgType.CMD.code, FrameHeader.FLAGS_NONE, body.size),
            body,
        )
        val error = expectProtocolError { codec.readFrame(ByteArrayInputStream(frame)) }
        assertEquals(FrameProtocolException.Kind.MALFORMED_JSON, error.kind)
    }

    // ─────────────────────── decodePayload 分发 ───────────────────────

    @Test
    fun `未注册解码器时 decodePayload 原样返回 JsonElement`() {
        val payload = JsonPrimitive("原始 JSON")
        val env = Envelope.of(MsgType.CMD, payload)
        assertSame(payload, codec.decodePayload(env))
    }

    @Test
    fun `注册解码器后 decodePayload 还原成具体载荷对象`() {
        val withProbe = JsonFrameCodec(
            payloadDecoders = mapOf(MsgType.CMD to ProbePayload.serializer()),
        )
        val decoded = withProbe.decodePayload(
            Envelope.of(MsgType.CMD, withProbe.encodeProbe(ProbePayload("落子", 3))),
        )
        assertEquals(ProbePayload("落子", 3), decoded)
    }

    @Test
    fun `空载荷可以被编码与解读`() = runTest {
        val env = Envelope.of(MsgType.PING, JsonNull)
        val decoded = codec.readFrame(ByteArrayInputStream(codec.encode(env)))
        assertEquals(JsonNull, decoded.payload)
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun JsonFrameCodec.encodeProbe(probe: ProbePayload): JsonElement =
        com.poria.net.proto.ProtocolJson.instance.encodeToJsonElement(
            ProbePayload.serializer(),
            probe,
        )

    private suspend fun expectProtocolError(block: suspend () -> Unit): FrameProtocolException {
        try {
            block()
        } catch (e: FrameProtocolException) {
            return e
        }
        fail("期望 FrameProtocolException，但没有抛出")
        throw IllegalStateException("unreachable")
    }

    /** 每次 read 都不跨过 [cut] 点、且最多 [maxChunk] 字节：模拟任意半包/粘包切分。 */
    private class SplitStream(
        private val data: ByteArray,
        private val cut: Int,
        private val maxChunk: Int,
    ) : InputStream() {

        private var pos = 0

        override fun read(): Int =
            if (pos < data.size) data[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            if (len == 0) return 0
            val limit = if (pos < cut) minOf(cut, data.size) else data.size
            val n = minOf(len, maxChunk, limit - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }
}

/** 测试用载荷类型：验证 `decodePayload` 的注册分发能力。 */
@Serializable
data class ProbePayload(val name: String, val value: Int)
