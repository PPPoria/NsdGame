package com.poria.netnsd.lobby

import com.poria.netnsd.link.TcpLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 大厅频道的**联机**测试：真开 socket（`TcpLink`）。
 *
 * 服务器那半边是 `LobbyChannel` 的服务端（它 `lobby != null`），客户端那半边是同一个类的客户端投影。
 * 覆盖：报到、两边快照一致、聊天、拒绝原因、准备、踢人（连接被断开并带原因）。
 *
 * 昵称/头像不在这个频道里（`ProfileChannelTest` 管那部分），所以这里只看 id 和座位。
 */
class LobbyOverLinkTest {

    private class Host(val link: TcpLink, val channel: LobbyChannel, val port: Int)

    private fun startHost(roomName: String = "测试房间"): Host {
        val channel = LobbyChannel(roomName = roomName, host = true)
        val link = TcpLink().apply { register(channel) }
        val port = link.host(0)
        link.send(LobbyProtocol.NS, LobbyProtocol.encode(LobbyProtocol.Command.Join))
        return Host(link, channel, port)
    }

    private fun joinGuest(port: Int): Pair<TcpLink, LobbyChannel> {
        val channel = LobbyChannel(roomName = null, host = false)
        val link = TcpLink().apply { register(channel) }
        link.join("127.0.0.1", port)
        link.send(LobbyProtocol.NS, LobbyProtocol.encode(LobbyProtocol.Command.Join))
        return link to channel
    }

    private fun send(link: TcpLink, command: LobbyProtocol.Command) {
        link.send(LobbyProtocol.NS, LobbyProtocol.encode(command))
    }

    private fun await(timeoutMs: Long = 10_000, message: String = "等超时了", block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    private fun awaitSnapshot(channel: LobbyChannel) =
        await(message = "没等到快照") { channel.state.value.snapshot != null }

    @Test(timeout = 40_000)
    fun `报到之后两边拿到同一份快照`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            awaitSnapshot(host.channel)
            awaitSnapshot(guestChannel)

            val snapshot = host.channel.state.value.snapshot!!
            assertEquals(
                "快照里只有身份和座位",
                listOf(LobbyPlayer(host.link.playerId, 0), LobbyPlayer(guest.playerId, 1)),
                snapshot.players,
            )
            assertEquals("同一份快照两边一模一样", snapshot, guestChannel.state.value.snapshot)
            assertEquals("房主标记来自回环那条连接", host.link.playerId, snapshot.hostId)
            assertEquals("本机身份也是链路分的", guest.playerId, guestChannel.state.value.myId)
            assertTrue(guestChannel.state.value.online)
            assertFalse(guestChannel.state.value.iAmHost)
            assertTrue(host.channel.state.value.iAmHost)
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `聊天进快照，两边都看得到`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            awaitSnapshot(host.channel)
            send(guest, LobbyProtocol.Command.Say("开一局？"))

            await(message = "服务器没收到这句") {
                host.channel.state.value.snapshot?.messages?.lastOrNull()?.text == "开一局？"
            }
            await(message = "客人自己没收到回显") {
                guestChannel.state.value.snapshot?.messages?.lastOrNull()?.text == "开一局？"
            }
            assertEquals(
                "消息只带 id，昵称是 profile 频道的事",
                guest.playerId,
                guestChannel.state.value.snapshot!!.messages.last().playerId,
            )
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `客人改设置会被拒绝，只回到他自己`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            awaitSnapshot(host.channel)
            send(guest, LobbyProtocol.Command.SetTableSize(3))

            await(message = "没收到拒绝原因") {
                guestChannel.state.value.note == "只有房主能改设置"
            }
            assertEquals("快照里的设置没变", 4, guestChannel.state.value.snapshot!!.tableSize)
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `客人准备之后服务器那边就能开局`() {
        val host = startHost()
        val (guest, _) = joinGuest(host.port)

        try {
            awaitSnapshot(host.channel)
            send(guest, LobbyProtocol.Command.Ready(true))

            await(message = "服务器那边没变成可开局") {
                host.channel.state.value.snapshot?.canStart == true
            }
            assertEquals(
                listOf(guest.playerId),
                host.channel.state.value.snapshot!!.readyIds,
            )
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `服务器踢人：名册少一个，被踢的连接断开并带原因`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            awaitSnapshot(host.channel)
            send(host.link, LobbyProtocol.Command.Kick(guest.playerId))

            await(message = "名册没少人") {
                host.channel.state.value.snapshot?.players?.size == 1
            }
            await(message = "被踢的人没断开") {
                guestChannel.state.value.phase == SessionPhase.CLOSED
            }
            assertEquals("房主把你请出了房间", guestChannel.state.value.note)
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `同一条连接进房两次会被拒，名册不变`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            awaitSnapshot(guestChannel)
            send(guest, LobbyProtocol.Command.Join)

            await(message = "第二次 Join 没有回拒绝") {
                guestChannel.state.value.note == "你已经在这间房里了"
            }
            assertEquals(2, host.channel.state.value.snapshot!!.players.size)
        } finally {
            guest.close()
            host.link.close()
        }
    }
}
