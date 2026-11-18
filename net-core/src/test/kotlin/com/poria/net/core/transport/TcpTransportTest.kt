package com.poria.net.core.transport

import com.poria.net.proto.Envelope
import com.poria.net.proto.MsgType
import com.poria.net.proto.ProtocolVersion
import com.poria.net.proto.frame.FrameHeader
import com.poria.net.proto.frame.JsonFrameCodec
import com.poria.net.proto.frame.Message
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `:net-core/transport` 验收测试（对应 ARCHITECTURE.md §8 M0 验收标准 ③⑤ 与 §9.2）。
 *
 * 归属：传输与消息协议。全部用 `127.0.0.1:0`（系统分配端口，永不冲突、可并行）。
 */
class TcpTransportTest {

    /** 房主侧：只 listen。 */
    private lateinit var hostTransport: TcpTransport

    /**
     * 客端侧：只 connect —— 对应真实部署（房主与客端在不同进程 / 不同设备上）。
     *
     * ADR-013 之后线程池是 cached 的（线程数随连接数伸缩），所以**单实例既 listen 又 connect
     * 也不会再抽干线程**；本类的 `单实例同时 listen 与 connect` 用例专门守住这条回归。
     * 其余用例仍按真实部署形状拆成两个实例，避免测试掩盖角色差异。
     */
    private lateinit var guestTransport: TcpTransport

    private val codec = JsonFrameCodec()

    @Before
    fun setUp() {
        hostTransport = TcpTransport()
        guestTransport = TcpTransport()
    }

    @After
    fun tearDown() {
        hostTransport.close()
        guestTransport.close()
    }

    private fun frame(type: MsgType, payload: JsonElement = JsonNull, seq: Long = 0): Message =
        codec.encode(Envelope.of(type, payload, seq = seq))

    private suspend fun decode(msg: Message): Envelope =
        codec.readFrame(ByteArrayInputStream(msg))

    // ─────────────── ADR-013：线程池不再是固定常量 ───────────────

    @Test
    fun `单实例同时 listen 与 connect：6 客端并发不抽干线程`() = runBlocking {
        // ADR-008 的固定 8 线程在这里会死：6 条客端连接 + 6 条房主侧连接 = 12 个长期阻塞的读协程，
        // 写协程拿不到线程 → 表现为 `写超时 10000ms`。ADR-013 改成 cached 池后必须通过。
        val shared = TcpTransport()
        try {
            val listener = shared.listen(0)
            val port = listener.localPort

            val accepting = async {
                withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.take(GUEST_COUNT).toList() }
            }
            val dialers = (0 until GUEST_COUNT).map {
                async { shared.connect(Endpoint.Tcp("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
            }.awaitAll()
            val hosts = accepting.await()

            // 双向各发一轮：只要线程被抽干，这里就会等到写超时（10s）而不是 3s 内失败
            dialers.forEachIndexed { i, dialer ->
                dialer.send(frame(MsgType.CMD, JsonPrimitive("cmd-$i")))
            }
            val fromGuests = hosts
                .map { host -> async { withTimeout(MSG_TIMEOUT_MS) { decode(host.incoming.first()) } } }
                .awaitAll()
                .map { env -> env.payload.jsonPrimitive.content }
                .toSet()
            assertEquals((0 until GUEST_COUNT).map { "cmd-$it" }.toSet(), fromGuests)

            hosts.forEachIndexed { i, host ->
                host.send(frame(MsgType.EVENT_BATCH, JsonPrimitive("evt-$i"), seq = i.toLong()))
            }
            val fromHost = dialers
                .map { dialer -> async { withTimeout(MSG_TIMEOUT_MS) { decode(dialer.incoming.first()) } } }
                .awaitAll()
                .map { env -> env.payload.jsonPrimitive.content }
                .toSet()
            assertEquals((0 until GUEST_COUNT).map { "evt-$it" }.toSet(), fromHost)

            listener.close()
        } finally {
            shared.close()
        }
    }

    @Test
    fun `仍可显式指定线程数上限（兜底旋钮）`() = runBlocking {
        // maxIoThreads 非 null → fixed 池；6 条连接 + accept + 写要靠 16 个线程撑住，正常收发
        val bounded = TcpTransport(maxIoThreads = 16)
        try {
            val listener = bounded.listen(0)
            val accepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            val dialer = bounded.connect(Endpoint.Tcp("127.0.0.1", listener.localPort), CONNECT_TIMEOUT_MS)
            val host = accepting.await()

            dialer.send(frame(MsgType.PING))
            withTimeout(MSG_TIMEOUT_MS) { host.incoming.first() }
            listener.close()
        } finally {
            bounded.close()
        }
    }

    @Test
    fun `fixed 池线程数低于下限时构造即失败（不让 P0 被悄悄装回来）`() {
        val error = runCatching { TcpTransport(maxIoThreads = 4) }.exceptionOrNull()

        assertTrue("maxIoThreads=4 应在构造时被 require 挡住，实际 $error", error is IllegalArgumentException)
        assertTrue(
            "错误信息应指回 ADR-013：${error?.message}",
            error?.message.orEmpty().contains("ADR-013"),
        )
    }

    // ───────────────────────── 往返 ─────────────────────────

    @Test
    fun `一条消息双向往返且永不丢半包`() = runBlocking {
        val listener = hostTransport.listen(0)
        try {
            val accepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            val dialer = guestTransport.connect(
                Endpoint.Tcp("127.0.0.1", listener.localPort),
                CONNECT_TIMEOUT_MS,
            )
            val host = accepting.await()

            // 大载荷，专门压半包：一条 200KB 的消息必然被内核切成多段
            val big = frame(MsgType.SNAPSHOT, JsonPrimitive("x".repeat(200 * 1024)))
            dialer.send(big)
            assertArrayEquals(big, withTimeout(MSG_TIMEOUT_MS) { host.incoming.first() })

            val welcome = frame(MsgType.WELCOME, JsonPrimitive("hello"))
            host.send(welcome)
            assertArrayEquals(welcome, withTimeout(MSG_TIMEOUT_MS) { dialer.incoming.first() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun `1000 条连续发送顺序完全正确且无粘包`() = runBlocking {
        val listener = hostTransport.listen(0)
        try {
            val accepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            val dialer = guestTransport.connect(
                Endpoint.Tcp("127.0.0.1", listener.localPort),
                CONNECT_TIMEOUT_MS,
            )
            val host = accepting.await()

            val collected = async {
                withTimeout(ORDER_TIMEOUT_MS) {
                    host.incoming.take(1000).toList().map { msg ->
                        // 一条 Message 必须恰好解出一帧（无粘包、无半包）
                        val env = decode(msg)
                        env.seq to env.payload.jsonPrimitive.content
                    }
                }
            }
            for (i in 0 until 1000) {
                dialer.send(frame(MsgType.EVENT_BATCH, JsonPrimitive("evt-$i"), seq = i.toLong()))
            }

            // 顺序 100% 正确：seq 与 payload 都必须严格按发送顺序
            assertEquals(
                (0 until 1000).map { it.toLong() to "evt-$it" },
                collected.await(),
            )
        } finally {
            listener.close()
        }
    }

    @Test
    fun `6 个客端并发加入且各自收到自己的消息`() = runBlocking {
        val listener = hostTransport.listen(0)
        try {
            val accepting = async {
                withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.take(GUEST_COUNT).toList() }
            }
            val dialers = (0 until GUEST_COUNT).map {
                async {
                    guestTransport.connect(Endpoint.Tcp("127.0.0.1", listener.localPort), CONNECT_TIMEOUT_MS)
                }
            }.awaitAll()
            val hosts = accepting.await()
            assertEquals(GUEST_COUNT, hosts.size)

            dialers.forEachIndexed { i, dialer ->
                dialer.send(frame(MsgType.CMD, JsonPrimitive("cmd-$i")))
            }

            val received = hosts
                .map { host -> async { withTimeout(MSG_TIMEOUT_MS) { decode(host.incoming.first()) } } }
                .awaitAll()
                .map { env -> env.payload.jsonPrimitive.content }

            assertEquals((0 until GUEST_COUNT).map { "cmd-$it" }.toSet(), received.toSet())
        } finally {
            listener.close()
        }
    }

    // ─────────────── 帧非法 → Protocol 断开，且不牵连别人 ───────────────

    @Test
    fun `声明超长载荷时以 Protocol 断开且不影响其它连接`() = runBlocking {
        val listener = hostTransport.listen(0)
        try {
            val goodAccepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            val good = guestTransport.connect(
                Endpoint.Tcp("127.0.0.1", listener.localPort),
                CONNECT_TIMEOUT_MS,
            )
            val goodHost = goodAccepting.await()

            val badAccepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            Socket("127.0.0.1", listener.localPort).use { raw ->
                val header = FrameHeader(
                    protoVersion = ProtocolVersion.CURRENT,
                    typeCode = MsgType.SNAPSHOT.code,
                    flags = FrameHeader.FLAGS_NONE,
                    payloadLength = FrameHeader.MAX_PAYLOAD_BYTES + 1,
                ).toByteArray()
                raw.getOutputStream().apply {
                    write(header)
                    flush()
                }

                val badHost = badAccepting.await()
                val reason = withTimeout(MSG_TIMEOUT_MS) { badHost.closed.first() }
                assertTrue("期望 CloseReason.Protocol，实际 $reason", reason is CloseReason.Protocol)
            }

            // 坏连接被断开之后，好连接必须照常收发
            val ping = frame(MsgType.PING)
            good.send(ping)
            assertArrayEquals(ping, withTimeout(MSG_TIMEOUT_MS) { goodHost.incoming.first() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun `表头 1 字节错乱立即以 Protocol 断开`() = runBlocking {
        val listener = hostTransport.listen(0)
        try {
            val accepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            Socket("127.0.0.1", listener.localPort).use { raw ->
                // magic 写成 "XX"，其余字段看起来完全合法
                val header = FrameHeader(
                    protoVersion = ProtocolVersion.CURRENT,
                    typeCode = MsgType.PING.code,
                    flags = FrameHeader.FLAGS_NONE,
                    payloadLength = 0,
                ).toByteArray().also {
                    it[0] = 'X'.code.toByte()
                    it[1] = 'X'.code.toByte()
                }
                raw.getOutputStream().apply {
                    write(header)
                    flush()
                }

                val host = accepting.await()
                val reason = withTimeout(MSG_TIMEOUT_MS) { host.closed.first() }
                assertTrue("期望 CloseReason.Protocol，实际 $reason", reason is CloseReason.Protocol)
                assertTrue(
                    "Protocol 原因里应说明是 magic 不对：$reason",
                    (reason as CloseReason.Protocol).message.contains("magic"),
                )
            }
        } finally {
            listener.close()
        }
    }

    // ───────────────────────── 关闭语义 ─────────────────────────

    @Test
    fun `一个连接违规只断自己：其它连接与监听器都不受影响`() = runBlocking {
        val listener = hostTransport.listen(0)
        val port = listener.localPort
        try {
            // 3 条正常连接 + 1 条违规连接（违规的排最后，便于按 accept 顺序识别）
            val accepting = async {
                withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.take(3).toList() }
            }
            val goodDialers = (0 until 3).map {
                async { guestTransport.connect(Endpoint.Tcp("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
            }.awaitAll()
            val goodHosts = accepting.await()

            // accept 顺序与 connect 顺序**没有契约**，必须用一条身份消息把 host ↔ dialer 对上号
            val hostByDialer = mutableMapOf<Int, Connection>()
            val firstMessages = goodHosts.map { host ->
                async { withTimeout(MSG_TIMEOUT_MS) { decode(host.incoming.first()) } }
            }
            goodDialers.forEachIndexed { i, dialer ->
                dialer.send(frame(MsgType.PING, JsonPrimitive("id-$i")))
            }
            firstMessages.awaitAll().forEachIndexed { hostIndex, env ->
                val dialerIndex = env.payload.jsonPrimitive.content.removePrefix("id-").toInt()
                hostByDialer[dialerIndex] = goodHosts[hostIndex]
            }
            assertEquals("3 条连接的 host 侧都应能对上号", 3, hostByDialer.size)

            val badAccepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            Socket("127.0.0.1", port).use { raw ->
                val header = FrameHeader(
                    protoVersion = ProtocolVersion.CURRENT,
                    typeCode = MsgType.PING.code,
                    flags = FrameHeader.FLAGS_NONE,
                    payloadLength = FrameHeader.MAX_PAYLOAD_BYTES + 1,
                ).toByteArray()
                raw.getOutputStream().apply {
                    write(header)
                    flush()
                }
                val badHost = badAccepting.await()
                val badReason = withTimeout(MSG_TIMEOUT_MS) { badHost.closed.first() }
                assertTrue("违规连接应被断开，实际 $badReason", badReason is CloseReason.Protocol)

                // ① 另外 3 条连接必须还活着，且双向可用
                goodDialers.forEachIndexed { i, dialer ->
                    val host = hostByDialer.getValue(i)

                    host.send(frame(MsgType.PING, JsonPrimitive("pong-$i")))
                    val seen = withTimeout(MSG_TIMEOUT_MS) { dialer.incoming.first() }
                    assertEquals("pong-$i", decode(seen).payload.jsonPrimitive.content)

                    dialer.send(frame(MsgType.PONG, JsonPrimitive("ack-$i")))
                    val hostSeen = withTimeout(MSG_TIMEOUT_MS) { host.incoming.first() }
                    assertEquals("ack-$i", decode(hostSeen).payload.jsonPrimitive.content)
                }

                // ② 监听器也必须还在，能继续接受新连接
                val lateAccepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
                val late = guestTransport.connect(Endpoint.Tcp("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                val lateHost = lateAccepting.await()
                late.send(frame(MsgType.HELLO, JsonPrimitive("late")))
                val hello = withTimeout(MSG_TIMEOUT_MS) { lateHost.incoming.first() }
                assertEquals("late", decode(hello).payload.jsonPrimitive.content)
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `对端不消费时按写超时断开而不是挂死`() = runBlocking {
        // 专用传输：写超时压到 500ms，免得测试真等 10s（默认值由 ADR-008 定）
        val slow = TcpTransport(writeTimeoutMs = 500)
        try {
            val listener = slow.listen(0)
            val accepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            Socket().use { raw ->
                // 对端只连不读，并且把接收缓冲压到 1KB → 内核腾挪空间很快耗尽
                raw.receiveBufferSize = 1024
                raw.connect(InetSocketAddress("127.0.0.1", listener.localPort), CONNECT_TIMEOUT_MS.toInt())
                val host = accepting.await()

                val big = frame(MsgType.SNAPSHOT, JsonPrimitive("x".repeat(200 * 1024)))
                val failure = runCatching { repeat(200) { host.send(big) } }.exceptionOrNull()

                assertTrue("对端不消费时必须触发写超时，实际没有失败", failure is IOException)
                assertTrue(
                    "错误信息应说明是写超时：$failure",
                    (failure as IOException).message.orEmpty().contains("写超时"),
                )

                // 写超时之后连接必须被关掉（`Io`），而不是半死不活
                val reason = withTimeout(MSG_TIMEOUT_MS) { host.closed.first() }
                assertTrue("期望 CloseReason.Io，实际 $reason", reason is CloseReason.Io)
            }
            listener.close()
        } finally {
            slow.close()
        }
    }

    @Test
    fun `本地关闭后对端看到 Eof 或 RemoteClosed`() = runBlocking {
        val listener = hostTransport.listen(0)
        try {
            val accepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            val dialer = guestTransport.connect(
                Endpoint.Tcp("127.0.0.1", listener.localPort),
                CONNECT_TIMEOUT_MS,
            )
            val host = accepting.await()

            dialer.close(CloseReason.LocalClose)

            val peerReason = withTimeout(MSG_TIMEOUT_MS) { host.closed.first() }
            assertTrue(
                "对端应看到 Eof 或 RemoteClosed，实际 $peerReason",
                peerReason is CloseReason.Eof || peerReason is CloseReason.RemoteClosed,
            )
            assertEquals(CloseReason.LocalClose, withTimeout(MSG_TIMEOUT_MS) { dialer.closed.first() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun `连接关闭后 send 立即失败而不是干等`() = runBlocking {
        val listener = hostTransport.listen(0)
        try {
            val accepting = async { withTimeout(CONNECT_TIMEOUT_MS) { listener.incoming.first() } }
            val dialer = guestTransport.connect(
                Endpoint.Tcp("127.0.0.1", listener.localPort),
                CONNECT_TIMEOUT_MS,
            )
            accepting.await()

            dialer.close(CloseReason.LocalClose)
            try {
                dialer.send(frame(MsgType.PING))
                fail("期望关闭后 send 抛 IOException")
            } catch (expected: IOException) {
                // 期望路径
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun `监听器关闭后端口不再接受新连接`() = runBlocking {
        val listener = hostTransport.listen(0)
        val port = listener.localPort
        listener.close()

        val failed = runCatching {
            guestTransport.connect(Endpoint.Tcp("127.0.0.1", port), CONNECT_TIMEOUT_MS)
        }.exceptionOrNull()
        assertTrue("监听关闭后不应还能连上，实际 $failed", failed is IOException)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val MSG_TIMEOUT_MS = 5_000L
        const val ORDER_TIMEOUT_MS = 20_000L
        const val GUEST_COUNT = 6
    }
}
