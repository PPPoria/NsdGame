package com.poria.netnsd.room

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
 * 房主那半边是 `LobbyChannel` 的服务端（它 `room != null`），客人那半边是同一个类的客户端投影。
 * 覆盖：报到、两边快照一致、聊天、拒绝原因、准备、踢人（连接被断开并带原因）、重名。
 */
class LobbyOverLinkTest {

    private class Host(val link: TcpLink, val channel: LobbyChannel, val port: Int)

    private fun startHost(roomName: String = "测试房间"): Host {
        val channel = LobbyChannel(roomName = roomName, host = true)
        val link = TcpLink().apply { register(channel) }
        val port = link.host(0)
        link.send(LobbyProtocol.NS, LobbyProtocol.encode(LobbyProtocol.Command.Join("房主")))
        return Host(link, channel, port)
    }

    private fun joinGuest(port: Int, nickname: String): Pair<TcpLink, LobbyChannel> {
        val channel = LobbyChannel(roomName = null, host = false)
        val link = TcpLink().apply { register(channel) }
        link.join("127.0.0.1", port)
        link.send(LobbyProtocol.NS, LobbyProtocol.encode(LobbyProtocol.Command.Join(nickname)))
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
        val (guest, guestChannel) = joinGuest(host.port, "客人")

        try {
            awaitSnapshot(host.channel)
            awaitSnapshot(guestChannel)

            val hostSnapshot = host.channel.state.value.snapshot!!
            assertEquals(listOf("房主", "客人"), hostSnapshot.players.map { it.name })
            assertEquals("同一份快照两边一模一样", hostSnapshot, guestChannel.state.value.snapshot)
            assertTrue("房主标记来自回环那条连接", hostSnapshot.player(host.link.playerId)!!.host)
            assertFalse(guestChannel.state.value.snapshot!!.player(guest.playerId)!!.host)
            assertEquals("本机身份也是房主分的", guest.playerId, guestChannel.state.value.myId)
            assertTrue(guestChannel.state.value.online)
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `聊天进快照，两边都看得到`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port, "客人")

        try {
            awaitSnapshot(host.channel)
            send(guest, LobbyProtocol.Command.Say("开一局？"))

            await(message = "房主没收到这句") {
                host.channel.state.value.snapshot?.messages?.lastOrNull()?.text == "开一局？"
            }
            await(message = "客人自己没收到回显") {
                guestChannel.state.value.snapshot?.messages?.lastOrNull()?.text == "开一局？"
            }
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `客人改设置会被拒绝，只回到他自己`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port, "客人")

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
    fun `客人准备之后房主那边就能开局`() {
        val host = startHost()
        val (guest, _) = joinGuest(host.port, "客人")

        try {
            awaitSnapshot(host.channel)
            send(guest, LobbyProtocol.Command.Ready(true))

            await(message = "房主那边没变成可开局") {
                host.channel.state.value.snapshot?.canStart == true
            }
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `房主踢人：名册少一个，被踢的连接断开并带原因`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port, "客人")

        try {
            awaitSnapshot(host.channel)
            send(host.link, LobbyProtocol.Command.Kick(guest.playerId))

            await(message = "房主名册没少人") {
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
    fun `重名会被拒绝，进不了房间`() {
        val host = startHost()
        // 先等第一个人真的进了房，再让第二个人用同名报到——否则谁先到是随机的
        val (first, firstChannel) = joinGuest(host.port, "客人")
        try {
            awaitSnapshot(firstChannel)

            val (second, secondChannel) = joinGuest(host.port, "客人")
            try {
                await(message = "第二个同名的人没被拒：${secondChannel.state.value}") {
                    secondChannel.state.value.note == "昵称被占了，换一个"
                }
                assertNull("被拒的人没有快照", secondChannel.state.value.snapshot)
            } finally {
                second.close()
            }
        } finally {
            first.close()
            host.link.close()
        }
    }
}
