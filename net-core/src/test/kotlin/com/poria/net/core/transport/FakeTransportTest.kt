package com.poria.net.core.transport

import com.poria.net.proto.Envelope
import com.poria.net.proto.MsgType
import com.poria.net.proto.frame.JsonFrameCodec
import com.poria.net.proto.frame.Message
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FakeTransport` 测试：它是 M1 会话层单测的地基，所以先在这里钉住它的语义与 TcpTransport 一致。
 *
 * 归属：传输与消息协议。
 */
class FakeTransportTest {

    private val codec = JsonFrameCodec()

    private fun frame(type: MsgType, payload: JsonElement = JsonNull): Message =
        codec.encode(Envelope.of(type, payload))

    @Test
    fun `往返一条消息`() = runBlocking {
        val transport = FakeTransport()
        val listener = transport.listen(0)
        val accepting = async { withTimeout(TIMEOUT_MS) { listener.incoming.first() } }
        val dialer = transport.connect(Endpoint.Tcp("fake", listener.localPort), TIMEOUT_MS)
        val host = accepting.await()

        val ping = frame(MsgType.PING)
        dialer.send(ping)
        assertArrayEquals(ping, withTimeout(TIMEOUT_MS) { host.incoming.first() })

        val pong = frame(MsgType.PONG)
        host.send(pong)
        assertArrayEquals(pong, withTimeout(TIMEOUT_MS) { dialer.incoming.first() })

        transport.close()
    }

    @Test
    fun `一端主动关闭时对端看到 RemoteClosed`() = runBlocking {
        val transport = FakeTransport()
        val listener = transport.listen(0)
        val accepting = async { withTimeout(TIMEOUT_MS) { listener.incoming.first() } }
        val dialer = transport.connect(Endpoint.Tcp("fake", listener.localPort), TIMEOUT_MS)
        val host = accepting.await()

        dialer.close(CloseReason.LocalClose)

        assertEquals(CloseReason.LocalClose, withTimeout(TIMEOUT_MS) { dialer.closed.first() })
        assertEquals(CloseReason.RemoteClosed, withTimeout(TIMEOUT_MS) { host.closed.first() })

        transport.close()
    }

    @Test
    fun `关闭后 send 抛 IOException`() = runBlocking {
        val transport = FakeTransport()
        val listener = transport.listen(0)
        val accepting = async { withTimeout(TIMEOUT_MS) { listener.incoming.first() } }
        val dialer = transport.connect(Endpoint.Tcp("fake", listener.localPort), TIMEOUT_MS)
        accepting.await()

        dialer.close(CloseReason.LocalClose)
        val error = runCatching { dialer.send(frame(MsgType.PING)) }.exceptionOrNull()
        assertTrue("期望 IOException，实际 $error", error is IOException)

        transport.close()
    }

    @Test
    fun `载荷内容可以原样解回`() = runBlocking {
        val transport = FakeTransport()
        val listener = transport.listen(0)
        val accepting = async { withTimeout(TIMEOUT_MS) { listener.incoming.first() } }
        val dialer = transport.connect(Endpoint.Tcp("fake", listener.localPort), TIMEOUT_MS)
        val host = accepting.await()

        dialer.send(frame(MsgType.CMD, JsonPrimitive("落子到 (7,7)")))
        val decoded = codec.readFrame(ByteArrayInputStream(withTimeout(TIMEOUT_MS) { host.incoming.first() }))

        assertEquals(MsgType.CMD, decoded.type)
        assertEquals("落子到 (7,7)", decoded.payload.jsonPrimitive.content)

        transport.close()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
