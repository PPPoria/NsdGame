package com.poria.netnsd.link

import com.poria.net.Peer
import com.poria.net.PeerListener
import com.poria.net.Tcp
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链路层单测：**真开 socket**（`TcpServer` + `Tcp.connect`），但不碰 Android、不碰 NSD。
 *
 * 覆盖的是链路自己的职责：报到与身份分配、路由、未知频道、中转、断线通知、频道注册表和心跳/超时。
 * 业务频道（lobby / blob / 游戏）各自在自己的测试里验。
 */
class TcpLinkTest {

    /** 记下频道收到的所有东西，测试用阻塞队列等它们。 */
    private class Recorder(override val ns: String) : TcpHandler {
        var out: LinkOut? = null

        /** (from, payload)：`from` 为 null = 房主自己推的，否则是经中转来的发送者。 */
        val events = LinkedBlockingQueue<Pair<String?, String>>()
        val commands = LinkedBlockingQueue<Pair<String, String>>()
        val joined = LinkedBlockingQueue<PlayerRef>()
        val left = LinkedBlockingQueue<String>()
        val closed = LinkedBlockingQueue<String>()
        val linked = LinkedBlockingQueue<String>()

        override fun attach(out: LinkOut) {
            this.out = out
        }

        override fun onLinked(localId: String) {
            linked += localId
        }

        override fun onPeerJoined(player: PlayerRef) {
            joined += player
        }

        override fun onCommand(player: PlayerRef, payload: JsonElement?) {
            commands += player.id to (payload?.toString() ?: "null")
        }

        override fun onEvent(payload: JsonElement?, from: String?) {
            events += from to (payload?.toString() ?: "null")
        }

        override fun onPeerLeft(playerId: String) {
            left += playerId
        }

        override fun onLinkClosed(reason: String?) {
            closed += reason ?: "null"
        }

        fun pollLinked(): String? = linked.poll(5, TimeUnit.SECONDS)
    }

    /** 房主 + 一个客人连好了、handler 也注册好了。用完记得 close。 */
    private fun connectPair(
        hostHandler: Recorder = Recorder("test"),
        guestHandler: Recorder = Recorder("test"),
        beatMs: Long = TcpLink.DEFAULT_BEAT_INTERVAL_MS,
        timeoutMs: Long = TcpLink.DEFAULT_TIMEOUT_MS,
    ): Triple<TcpLink, TcpLink, Recorder> {
        val host = TcpLink(beatMs, timeoutMs)
        assertTrue(host.register(hostHandler))
        val port = host.host(0)

        val guest = TcpLink(beatMs, timeoutMs)
        assertTrue(guest.register(guestHandler))
        guest.join("127.0.0.1", port)
        return Triple(host, guest, guestHandler)
    }

    private fun awaitJoins(handler: Recorder, count: Int): Set<String> {
        val ids = HashSet<String>()
        repeat(count) {
            val player = handler.joined.poll(5, TimeUnit.SECONDS)
            if (player != null) ids += player.id
        }
        return ids
    }

    /**
     * 一个只会报到、不回 pong 的裸客户端：模拟 `nc` 和"假客户端"。
     *
     * @param hello 报到帧（默认是不带凭据的普通报到）
     * @param afterHello 报到帧发出去之后立刻执行的额外动作（伪造帧之类）
     * @return 连接 和 房主分给它的身份（从 welcome 帧里读到）
     */
    private fun rawClient(
        port: Int,
        hello: LinkFrame.Hello = LinkFrame.Hello(),
        afterHello: (Peer) -> Unit = {},
    ): Pair<Peer, LinkedBlockingQueue<String>> {
        val assigned = LinkedBlockingQueue<String>()
        val peer = Tcp.connect("127.0.0.1", port, object : PeerListener {
            override fun onOpen(peer: Peer) {
                peer.send(LinkCodec.encode(Frame(ns = LINK_NS, p = LinkCodec.encode(hello))))
                afterHello(peer)
            }

            override fun onLine(peer: Peer, line: String) {
                val frame = LinkCodec.decode(line) ?: return
                val msg = LinkCodec.decodeLinkMessage(frame.p) ?: return
                if (msg is LinkFrame.Welcome) assigned += msg.playerId
            }
        })
        return peer to assigned
    }

    // ── 报到与身份 ──────────────────────────────────────────────────────────

    @Test(timeout = 20_000)
    fun `报到时候由房主分配身份，两边都拿着同一份`() {
        val hostHandler = Recorder("test")
        val guestHandler = Recorder("test")
        val (host, guest, _) = connectPair(hostHandler, guestHandler)

        try {
            assertEquals("房主自己也有身份", setOf(host.playerId, guest.playerId), awaitJoins(hostHandler, 2))
            assertTrue("身份是房主排的号：${host.playerId}", host.playerId.matches(Regex("p\\d+")))
            assertTrue(guest.playerId.matches(Regex("p\\d+")))
            assertNotEquals("两个人不能撞号", host.playerId, guest.playerId)

            // 频道也被通知过身份，而且是同一份
            assertEquals(host.playerId, hostHandler.pollLinked())
            assertEquals(guest.playerId, guestHandler.pollLinked())
            assertEquals(guest.playerId, guest.localPlayer.id)
            assertTrue(host.localPlayer.isHost)
            assertFalse(guest.localPlayer.isHost)
        } finally {
            guest.close()
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `房主和客人通过同一个频道来回传数据`() {
        val hostHandler = Recorder("test")
        val host = TcpLink()
        assertTrue(host.register(hostHandler))
        val port = host.host(0)

        val guestHandler = Recorder("test")
        val guest = TcpLink()
        assertTrue(guest.register(guestHandler))
        guest.join("127.0.0.1", port)

        try {
            // 房主自己也走回环报到一次，所以是两个人
            assertEquals(setOf(host.playerId, guest.playerId), awaitJoins(hostHandler, 2))

            // 客人 → 房主：命令，带着"谁发的"
            guest.send("test", JsonPrimitive("hi"))
            val (from, payload) = hostHandler.commands.poll(5, TimeUnit.SECONDS)!!
            assertEquals(guest.playerId, from)
            assertEquals("\"hi\"", payload)

            // 房主 → 所有人：推送（房主自己的回环客户端也会收到一次）
            val sent = hostHandler.out!!.broadcast(JsonPrimitive("snap"))
            assertTrue("至少发给房主自己", sent >= 1)
            assertEquals("\"snap\"", guestHandler.events.poll(5, TimeUnit.SECONDS)?.second)
            assertEquals("\"snap\"", hostHandler.events.poll(5, TimeUnit.SECONDS)?.second)
        } finally {
            guest.close()
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `房主标记只给带着凭据的回环连接，客人冒充不了`() {
        val hostHandler = Recorder("test")
        val host = TcpLink()
        host.register(hostHandler)
        val port = host.host(0)

        val guest = TcpLink()
        guest.register(Recorder("test"))
        guest.join("127.0.0.1", port)

        // 假装是房主：同样是回环连上来的，但手里没有那份凭据
        val (liar, _) = rawClient(port, hello = LinkFrame.Hello(hostClaim = "猜的"))

        try {
            val joins = ArrayList<PlayerRef>()
            repeat(3) { hostHandler.joined.poll(5, TimeUnit.SECONDS)?.let(joins::add) }
            assertEquals("三个人都报到了：$joins", 3, joins.size)
            assertTrue("房主那条回环连接才是房主", joins.first { it.id == host.playerId }.isHost)
            assertFalse("客人不是房主", joins.first { it.id == guest.playerId }.isHost)
            assertFalse("没有凭据就冒充不了房主", joins.first { it.id != host.playerId && it.id != guest.playerId }.isHost)
        } finally {
            liar.close()
            guest.close()
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `没有注册的频道会被忽略，链路不崩`() {
        val hostHandler = Recorder("test")
        val (host, guest, _) = connectPair(hostHandler = hostHandler)

        try {
            // 先等报到完成：报到之前的帧会被当成"还没进门"拒掉
            assertEquals(setOf(host.playerId, guest.playerId), awaitJoins(hostHandler, 2))

            guest.send("nope", JsonPrimitive("随便"))
            // 后面这条还得正常到，说明链路没被前一条带崩
            guest.send("test", JsonPrimitive("still"))
            assertEquals("\"still\"", hostHandler.commands.poll(5, TimeUnit.SECONDS)?.second)
            assertTrue(guest.state.value.connected)
        } finally {
            guest.close()
            host.close()
        }
    }

    // ── 中转（星型点对点）──────────────────────────────────────────────────

    @Test(timeout = 20_000)
    fun `中转：客人发给另一个客人，落款是真实发送者`() {
        val hostHandler = Recorder("test")
        val host = TcpLink()
        host.register(hostHandler)
        val port = host.host(0)

        val firstHandler = Recorder("test")
        val first = TcpLink()
        first.register(firstHandler)
        first.join("127.0.0.1", port)

        val secondHandler = Recorder("test")
        val second = TcpLink()
        second.register(secondHandler)
        second.join("127.0.0.1", port)

        try {
            assertEquals(setOf(host.playerId, first.playerId, second.playerId), awaitJoins(hostHandler, 3))

            first.sendTo(second.playerId, "test", JsonPrimitive("私聊"))
            val (from, payload) = secondHandler.events.poll(5, TimeUnit.SECONDS)!!
            assertEquals(first.playerId, from)
            assertEquals("\"私聊\"", payload)
            assertTrue(
                "中转的帧不该进房主的频道处理：${hostHandler.commands}",
                hostHandler.commands.isEmpty(),
            )
        } finally {
            second.close()
            first.close()
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `中转：客人也能发给房主`() {
        val hostHandler = Recorder("test")
        val host = TcpLink()
        host.register(hostHandler)
        val port = host.host(0)

        val guest = TcpLink()
        guest.register(Recorder("test"))
        guest.join("127.0.0.1", port)

        try {
            assertEquals(setOf(host.playerId, guest.playerId), awaitJoins(hostHandler, 2))
            guest.sendTo(host.playerId, "test", JsonPrimitive("给房主"))
            val (from, payload) = hostHandler.events.poll(5, TimeUnit.SECONDS)!!
            assertEquals(guest.playerId, from)
            assertEquals("\"给房主\"", payload)
        } finally {
            guest.close()
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `中转会把客户端伪造的 from 盖掉`() {
        val hostHandler = Recorder("test")
        val host = TcpLink()
        host.register(hostHandler)
        val port = host.host(0)

        val guestHandler = Recorder("test")
        val guest = TcpLink()
        guest.register(guestHandler)
        guest.join("127.0.0.1", port)

        // 假客户端：报到后伪造一个"房主发的"帧，让房主转给客人
        val (raw, rawId) = rawClient(port) { peer ->
            peer.send(
                LinkCodec.encode(
                    Frame(ns = "test", to = guest.playerId, from = host.playerId, p = JsonPrimitive("骗你的")),
                ),
            )
        }

        try {
            assertEquals(3, awaitJoins(hostHandler, 3).size)
            val (from, payload) = guestHandler.events.poll(5, TimeUnit.SECONDS)!!
            assertEquals("服务端盖章：假客户端的真身份", rawId.poll(5, TimeUnit.SECONDS), from)
            assertEquals("\"骗你的\"", payload)
        } finally {
            raw.close()
            guest.close()
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `中转目标不存在时静默丢弃，链路照常`() {
        val hostHandler = Recorder("test")
        val (host, guest, _) = connectPair(hostHandler = hostHandler)

        try {
            assertEquals(setOf(host.playerId, guest.playerId), awaitJoins(hostHandler, 2))
            guest.sendTo("p404", "test", JsonPrimitive("没人"))
            guest.send("test", JsonPrimitive("还在"))
            assertEquals("\"还在\"", hostHandler.commands.poll(5, TimeUnit.SECONDS)?.second)
            assertTrue(guest.state.value.connected)
        } finally {
            guest.close()
            host.close()
        }
    }

    // ── 断线与关链路 ────────────────────────────────────────────────────────

    @Test(timeout = 20_000)
    fun `客人断开房主会收到 onPeerLeft`() {
        val hostHandler = Recorder("test")
        val (host, guest, _) = connectPair(hostHandler = hostHandler)

        try {
            awaitJoins(hostHandler, 2)
            val guestId = guest.playerId
            guest.close()
            assertEquals(guestId, hostHandler.left.poll(5, TimeUnit.SECONDS))
            assertFalse(host.state.value.players.any { it.id == guestId })
        } finally {
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `关链路会通知所有 Handler 原因`() {
        val handler = Recorder("test")
        val host = TcpLink()
        host.register(handler)

        host.close("收摊了")

        assertEquals("收摊了", handler.closed.poll(5, TimeUnit.SECONDS))
    }

    // ── 频道注册表 ──────────────────────────────────────────────────────────

    @Test(timeout = 10_000)
    fun `频道名不能重复，也挡掉链路保留名`() {
        val host = TcpLink()
        try {
            assertTrue(host.register(Recorder("lobby")))
            assertFalse("同名频道只能有一个", host.register(Recorder("lobby")))
            assertFalse("_link 是链路自己的", host.register(Recorder("_link")))
            assertEquals(listOf("lobby"), host.channels())

            assertTrue(host.unregister("lobby"))
            assertFalse(host.unregister("lobby"))
            assertTrue(host.channels().isEmpty())
        } finally {
            host.close()
        }
    }

    @Test(timeout = 20_000)
    fun `报到之后才注册的频道也会拿到身份`() {
        val (host, guest, _) = connectPair()

        try {
            val late = Recorder("late")
            assertTrue(guest.register(late))
            assertEquals(guest.playerId, late.pollLinked())
        } finally {
            guest.close()
            host.close()
        }
    }

    // ── 心跳 / 超时 ─────────────────────────────────────────────────────────

    @Test(timeout = 30_000)
    fun `心跳让空闲连接一直在线`() {
        val hostHandler = Recorder("test")
        val (host, guest, guestHandler) = connectPair(
            hostHandler = hostHandler,
            beatMs = 100,
            timeoutMs = 800,
        )

        try {
            awaitJoins(hostHandler, 2)
            // 空闲远超过 timeout：靠 ping/pong 撑着
            Thread.sleep(1_500)
            assertTrue("房主这边应该还连着", host.state.value.connected)
            assertTrue("客人这边应该还连着", guest.state.value.connected)
            assertTrue("没有人该被判死：${hostHandler.left}", hostHandler.left.isEmpty())
            assertTrue(guestHandler.left.isEmpty())
        } finally {
            guest.close()
            host.close()
        }
    }

    @Test(timeout = 30_000)
    fun `不回心跳的对端会被断开`() {
        val hostHandler = Recorder("test")
        val host = TcpLink(beatIntervalMs = 100, timeoutMs = 500)
        host.register(hostHandler)
        val port = host.host(0)

        // 一个"报到完就不吭声"的假客户端：不回 pong
        val (raw, rawId) = rawClient(port)

        try {
            val reported = rawId.poll(5, TimeUnit.SECONDS)
            assertTrue("房主分了身份：$reported", reported != null)
            assertEquals(2, awaitJoins(hostHandler, 2).size)
            assertEquals(reported, hostHandler.left.poll(15, TimeUnit.SECONDS))
        } finally {
            raw.close()
            host.close()
        }
    }
}
