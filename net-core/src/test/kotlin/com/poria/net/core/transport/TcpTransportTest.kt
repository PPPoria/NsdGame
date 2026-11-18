package com.poria.net.core.transport

import com.poria.net.proto.Envelope
import com.poria.net.proto.MsgType
import com.poria.net.proto.ProtocolVersion
import com.poria.net.proto.frame.FrameHeader
import com.poria.net.proto.frame.JsonFrameCodec
import com.poria.net.proto.frame.Message
import java.io.ByteArrayInputStream
import java.io.IOException
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
     * 客端侧：只 connect。
     *
     * 真实部署里这两者分别在不同进程 / 不同设备上，所以测试也分开建：
     * 每个实例有专属的 8 个 `nsdnet-N` 线程，而**每条连接的读循环会长期占住一个线程**。
     * 同一个实例同时 hold 12 条连接就会把线程池抽干（读协程全卡住，写协程拿不到线程）。
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
    fun `一条连接上 1000 条消息顺序完全正确`() = runBlocking {
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
                    host.incoming.take(1000).toList().map { decode(it).seq }
                }
            }
            for (i in 0 until 1000) {
                dialer.send(frame(MsgType.EVENT_BATCH, JsonPrimitive(i.toString()), seq = i.toLong()))
            }

            assertEquals((0 until 1000).map { it.toLong() }, collected.await())
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
