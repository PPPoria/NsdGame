package com.poria.netnsd.room

import com.poria.net.Peer
import com.poria.net.PeerListener
import com.poria.net.Tcp
import com.poria.net.TcpServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 快照要真的**整条**穿过 socket：`RoomProtocol` 的目标是 6KB，`:core` 的行协议上限是 8KB，
 * 中文一个字符 3 字节——这条测试用真 socket 把这两件事钉在一起（顺便验证 UTF-8 不会被切开）。
 *
 * 不碰 Android，所以 `./gradlew :app-net:test` 里就能跑。
 */
class SnapshotOverWireTest {

    @Test(timeout = 15_000)
    fun `接近预算上限的快照能整条穿过真实 socket`() {
        val received = LinkedBlockingQueue<String>()
        val sent = bigSnapshot()

        val server = TcpServer(
            0,
            object : PeerListener {
                override fun onOpen(peer: Peer) {
                    peer.send(RoomProtocol.snapshot(sent))
                }
            },
        ).start()

        val client = Tcp.connect(
            "127.0.0.1",
            server.localPort,
            object : PeerListener {
                override fun onLine(peer: Peer, line: String) {
                    received += line
                }
            },
        )

        try {
            val line = received.poll(10, TimeUnit.SECONDS)
            assertNotNull("一条快照都没收到", line)

            val decoded = (RoomProtocol.decodeEvent(line!!) as RoomEvent.Snapshot).snapshot
            assertEquals("整份快照必须能解回来", sent.players, decoded.players)
            assertEquals(sent.options, decoded.options)
            assertEquals(sent.roomName, decoded.roomName)
            assertEquals(sent.startHint, decoded.startHint)
            assertEquals("最新那条聊天必须原样", sent.messages.last(), decoded.messages.last())
        } finally {
            client.close()
            server.stop()
        }
    }

    /** 一份贴着预算上限的快照：人、玩法、聊天都塞满。 */
    private fun bigSnapshot(): RoomSnapshot {
        val long = "砖".repeat(RoomProtocol.MAX_TEXT)
        return RoomSnapshot(
            roomName = "小明的房间",
            hostId = "host-1",
            phase = RoomPhase.LOBBY,
            gameId = "azul",
            tableSize = 4,
            startHint = "等 小红 准备",
            options = linkedMapOf("grey" to true, "quick" to false, "harsh" to false),
            players = listOf(
                RoomPlayer("host-1", 0, "小明", host = true, ready = false),
                RoomPlayer("p2", 1, "小红", host = false, ready = true),
                RoomPlayer("p3", 3, "小刚", host = false, ready = false),
            ),
            messages = (1..5).map { RoomMessage(it, "p2", "第${it}条：$long") },
        )
    }
}
