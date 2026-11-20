package com.poria.netnsd.room

import com.poria.net.Peer
import com.poria.net.PeerListener
import com.poria.net.Tcp
import com.poria.net.TcpServer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片走真 socket 的端到端测试：房主按 id 分块发，客户端拼进 [BlobStore]。
 *
 * 覆盖的正是"同一条 wire 上传/下载"最容易写错的部分：块序、总块数、base64 往返、
 * 缺图时回一句原因而不是让对方干等。不碰 Android，`./gradlew :app-net:test` 直接跑。
 */
class BlobOverWireTest {

    private val hostDir = Files.createTempDirectory("blob-host").toFile()
    private val clientDir = Files.createTempDirectory("blob-client").toFile()

    @After
    fun tearDown() {
        hostDir.deleteRecursively()
        clientDir.deleteRecursively()
    }

    @Test(timeout = 30_000)
    fun `客户端按 id 拉图，房主分块发回来，内容一字不差`() {
        val bytes = ByteArray(10_000) { (it * 7 % 255).toByte() }
        val id = BlobStore.sha1Hex(bytes)
        val hostStore = BlobStore(hostDir)
        val clientStore = BlobStore(clientDir)
        assertTrue(hostStore.install(id, bytes))

        val assembled = CountDownLatch(1)
        val errors = LinkedBlockingQueue<String>()
        val wrongChunks = LinkedBlockingQueue<String>()

        val server = TcpServer(
            0,
            object : PeerListener {
                override fun onLine(peer: Peer, line: String) {
                    val command = RoomProtocol.decodeCommand(line)
                    if (command is RoomCommand.RequestBlob) {
                        // 这就是 RoomSession 服务端侧做的事：直接发，不经过房间锁
                        BlobSender.send(peer, hostStore, command.id)
                    }
                }
            },
        ).start()

        val client = Tcp.connect(
            "127.0.0.1",
            server.localPort,
            object : PeerListener {
                override fun onLine(peer: Peer, line: String) {
                    when (val event = RoomProtocol.decodeEvent(line)) {
                        is RoomEvent.Blob -> {
                            val done = clientStore.put(event.id, event.index, event.total, event.data)
                            if (done) assembled.countDown()
                        }

                        is RoomEvent.BlobError -> errors += event.reason
                        is RoomEvent.Error -> wrongChunks += event.reason
                        else -> Unit
                    }
                }
            },
        )

        try {
            client.send(RoomProtocol.requestBlob(id))
            assertTrue("图没拼完", assembled.await(15, TimeUnit.SECONDS))
            assertArrayEquals(bytes, clientStore.file(id)!!.readBytes())

            // 拉一张房主没有的：要回一句原因，而不是让对方干等
            client.send(RoomProtocol.requestBlob("f".repeat(40)))
            val reason = errors.poll(15, TimeUnit.SECONDS)
            assertNotNull("缺图要给个说法", reason)
            assertNull("正常流程不该有别的错误：$wrongChunks", wrongChunks.poll())
        } finally {
            client.close()
            server.stop()
        }
    }
}
