package com.poria.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 真开 socket 的集成测试：全部走 127.0.0.1，不需要模拟器。
 *
 * 包括房主自己那条回环连接——它和远端客人走的是同一条路径。
 */
class TcpTest {

    private val servers = mutableListOf<TcpServer>()
    private val peers = mutableListOf<Peer>()

    @After
    fun tearDown() {
        peers.forEach { it.close() }
        servers.forEach { it.stop() }
    }

    // ── 连上 / 收发 ─────────────────────────────────────────────────────────

    @Test
    fun `客户端连上来能双向收发`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)
        assertTrue("端口 0 应该由系统分配一个真端口", server.localPort > 0)

        val clientEvents = RecordingListener()
        val client = connect(server.localPort, clientEvents)

        val peer = serverEvents.opened.next("服务端 onOpen")
        clientEvents.opened.next("客户端 onOpen")
        assertTrue(peer.isOpen)
        assertEquals(1, server.peers().size)

        assertTrue(client.send("我是玩家 2"))
        assertEquals("我是玩家 2", serverEvents.lines.next("服务端收到消息"))

        assertTrue(peer.send("欢迎上车 🎴"))
        assertEquals("欢迎上车 🎴", clientEvents.lines.next("客户端收到消息"))
    }

    @Test
    fun `多个客户端连同一个服务端时广播发给所有人`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)

        val first = RecordingListener()
        val second = RecordingListener()
        connect(server.localPort, first)
        connect(server.localPort, second)
        serverEvents.opened.next("第一个连接")
        serverEvents.opened.next("第二个连接")
        assertEquals(2, server.peers().size)

        assertEquals(2, server.broadcast("开局，轮到座位 0"))

        assertEquals("开局，轮到座位 0", first.lines.next("客户端 1"))
        assertEquals("开局，轮到座位 0", second.lines.next("客户端 2"))
    }

    @Test
    fun `同一个连接连发多条，顺序不变`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)
        val client = connect(server.localPort, RecordingListener())
        serverEvents.opened.next("服务端 onOpen")

        val sent = (1..50).map { "第 $it 手" }
        sent.forEach { assertTrue(client.send(it)) }

        assertEquals(sent, List(sent.size) { serverEvents.lines.next("第 ${it + 1} 条消息") })
    }

    @Test
    fun `接近上限的大消息也能过`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)
        val clientEvents = RecordingListener()
        val client = connect(server.localPort, clientEvents)
        val peer = serverEvents.opened.next("服务端 onOpen")

        val big = "牌".repeat(Wire.MAX_LINE_BYTES / 4) // 每字 3 字节，留足余量
        assertTrue(client.send(big))
        assertEquals(big, serverEvents.lines.next("服务端收到大消息"))

        assertTrue(peer.send(big))
        assertEquals(big, clientEvents.lines.next("客户端收到大消息"))
    }

    // ── 房主自己那条回环连接 ────────────────────────────────────────────────

    @Test
    fun `房主用回环连自己的服务端，和远端客人没有区别`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)

        // 房主玩家用的就是客人那个函数，只是 IP 写 127.0.0.1
        val hostPlayer = RecordingListener()
        val hostPeer = connect(server.localPort, hostPlayer)
        val peer = serverEvents.opened.next("服务端 onOpen")

        assertEquals("房主那条连接的对端就是本机", "127.0.0.1", peer.remoteHost)
        assertTrue("房主出牌也走 Peer.send", hostPeer.send("PLAY|red|5"))
        assertEquals("PLAY|red|5", serverEvents.lines.next("服务端收到房主的操作"))

        assertEquals("广播也要回到房主自己手上", 1, server.broadcast("STATE|轮到座位 1"))
        assertEquals("STATE|轮到座位 1", hostPlayer.lines.next("房主收到广播"))
    }

    // ── 断开 ────────────────────────────────────────────────────────────────

    @Test
    fun `客户端主动断开时两边各回调一次`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)
        val clientEvents = RecordingListener()
        val client = connect(server.localPort, clientEvents)
        serverEvents.opened.next("服务端 onOpen")

        client.close()

        assertEquals("自己关的算 LOCAL", CloseCause.LOCAL, clientEvents.closed.next("客户端 onClosed").cause)
        assertEquals("对面关的算 REMOTE", CloseCause.REMOTE, serverEvents.closed.next("服务端 onClosed").cause)

        assertFalse(client.isOpen)
        assertFalse(client.send("还有人在吗"))
        assertNull("服务端不该再收到消息", serverEvents.lines.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `服务端 stop 会把所有连接都断掉`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)
        val clientEvents = RecordingListener()
        val client = connect(server.localPort, clientEvents)
        serverEvents.opened.next("服务端 onOpen")

        server.stop()

        assertEquals(CloseCause.LOCAL, serverEvents.closed.next("服务端自己的连接").cause)
        assertEquals(CloseCause.REMOTE, clientEvents.closed.next("客户端看到对面消失").cause)
        assertFalse(server.isRunning)
        assertEquals(-1, server.localPort)
        assertFalse(client.send("已经没人听了"))
    }

    @Test
    fun `一端发超长的一行会被当作协议错误断开`() {
        val serverEvents = RecordingListener()
        val server = startServer(serverEvents)

        // 绕过 Tcp.connect 直接下脏数据：模拟"对面的实现写错了/有人乱连"
        Socket("127.0.0.1", server.localPort).use { raw ->
            raw.getOutputStream().write(ByteArray(Wire.MAX_LINE_BYTES + 10) { 'x'.code.toByte() })
            raw.getOutputStream().flush()

            val closed = serverEvents.closed.next("服务端 onClosed")
            assertEquals(CloseCause.ERROR, closed.cause)
            assertNotNull("断开原因要能看见", closed.reason)
        }
    }

    // ── 异常路径 ────────────────────────────────────────────────────────────

    @Test
    fun `连不上的端口抛 IOException 而不是卡住`() {
        // 先占用一个端口再放掉，端口号短期内不会再被分出去
        val deadPort = ServerSocket(0).use { it.localPort }
        assertThrows(IOException::class.java) {
            Tcp.connect("127.0.0.1", deadPort, RecordingListener(), timeoutMs = 1_000)
        }
    }

    @Test
    fun `重复 start 会拒绝，不会偷偷起两个 accept 线程`() {
        val server = startServer(RecordingListener())
        assertThrows(IllegalStateException::class.java) { server.start() }
    }

    @Test
    fun `广播会先挡掉本机写错的消息`() {
        val server = startServer(RecordingListener())
        assertThrows(IllegalArgumentException::class.java) { server.broadcast("两行\n消息") }
        assertThrows(IllegalArgumentException::class.java) {
            server.broadcast("x".repeat(Wire.MAX_LINE_BYTES + 1))
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun startServer(events: RecordingListener): TcpServer =
        TcpServer(port = 0, listener = events).start().also { servers += it }

    private fun connect(port: Int, events: RecordingListener): Peer =
        Tcp.connect("127.0.0.1", port, events).also { peers += it }
}
