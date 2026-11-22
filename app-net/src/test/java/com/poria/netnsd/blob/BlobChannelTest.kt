package com.poria.netnsd.blob

import com.poria.netnsd.link.LinkOut
import com.poria.netnsd.link.TcpLink
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片频道的单测。
 *
 * 分两半：
 * - **本地**：用假的 [LinkOut] 直接喂 payload，验证进度/ready/failed 这套接口（不碰网络）；
 * - **联机**：真开 socket（`TcpLink`），验证"向房主要图"和"客人之间经房主中转拿图"。
 */
class BlobChannelTest {

    private val hostDir = Files.createTempDirectory("blob-host").toFile()
    private val guestDir = Files.createTempDirectory("blob-guest").toFile()
    private val secondDir = Files.createTempDirectory("blob-guest2").toFile()

    @After
    fun tearDown() {
        listOf(hostDir, guestDir, secondDir).forEach { it.deleteRecursively() }
    }

    /** 只记下"我发了什么"，不会真的出门。 */
    private class FakeOut : LinkOut {
        val sent = LinkedBlockingQueue<Pair<String, JsonElement?>>()

        override fun sendTo(playerId: String, payload: JsonElement?): Boolean {
            sent += playerId to payload
            return true
        }

        override fun broadcast(payload: JsonElement?): Int = 0

        override fun broadcastEach(build: (playerId: String) -> JsonElement?): Int = 0

        override fun sendCommand(payload: JsonElement?): Boolean = false

        override fun disconnect(playerId: String, reason: String): Boolean = false
    }

    private fun split(bytes: ByteArray): List<ByteArray> {
        val size = BlobLimits.CHUNK_BYTES
        val count = (bytes.size + size - 1) / size
        return (0 until count).map { index ->
            bytes.copyOfRange(index * size, minOf((index + 1) * size, bytes.size))
        }
    }

    // ── 本地：进度接口 ──────────────────────────────────────────────────────

    @Test
    fun `进度按字节累加，收齐后从进度表里消失并进 ready`() {
        val channel = BlobChannel(BlobStore(guestDir))
        channel.attach(FakeOut())

        val bytes = ByteArray(BlobLimits.CHUNK_BYTES * 2 + 100) { (it % 251).toByte() }
        val id = BlobStore.sha1Hex(bytes)
        val chunks = split(bytes)
        assertTrue("要图", channel.request(id, "owner"))

        val placeholder = channel.progress.value[id]
        assertNotNull("要有占位", placeholder)
        assertEquals("还不知道总大小", 0L, placeholder!!.total)

        val first = chunks.first()
        channel.onEvent(
            BlobCodec.encode(BlobPayload.Chunk(id, 0, chunks.size, bytes.size, first)),
            from = "owner",
        )

        val afterFirst = channel.progress.value[id]
        assertNotNull(afterFirst)
        assertEquals("收了一块", first.size.toLong(), afterFirst!!.received)
        assertEquals("总大小来自 size 字段", bytes.size.toLong(), afterFirst.total)
        assertTrue("百分比能算：${afterFirst.percent}", afterFirst.percent in 1..99)

        chunks.drop(1).forEachIndexed { index, data ->
            channel.onEvent(
                BlobCodec.encode(BlobPayload.Chunk(id, index + 1, chunks.size, bytes.size, data)),
                from = "owner",
            )
        }

        assertTrue("收齐了", channel.contains(id))
        assertNull("进度表里不该再有它", channel.progress.value[id])
        assertEquals("ready 会通知一次", id, channel.ready.replayCache.last())
        assertArrayEquals(bytes, channel.file(id)!!.readBytes())
    }

    @Test
    fun `对方说没有时，进度撤掉并进 failed`() {
        val channel = BlobChannel(BlobStore(guestDir))
        channel.attach(FakeOut())

        val id = BlobStore.sha1Hex(ByteArray(10) { 1 })
        assertTrue(channel.request(id, "owner"))
        channel.onEvent(BlobCodec.encode(BlobPayload.Missing(id)), from = "owner")

        assertNull("进度不该留着", channel.progress.value[id])
        assertEquals("failed 里有原因", id, channel.failed.replayCache.last().id)
        assertFalse(channel.contains(id))
    }

    @Test
    fun `同一张图正在拉的时候不再重复要`() {
        val channel = BlobChannel(BlobStore(guestDir))
        val out = FakeOut()
        channel.attach(out)

        val id = BlobStore.sha1Hex(ByteArray(10) { 2 })
        assertTrue(channel.request(id, "owner-1"))
        assertFalse("已经在拉了", channel.request(id, "owner-2"))
        assertEquals("只发了一条 want", 1, out.sent.size)
        assertEquals("发给第一个拥有者", "owner-1", out.sent.poll()?.first)
    }

    @Test
    fun `install 返回内容 id，空图不收`() {
        val channel = BlobChannel(BlobStore(guestDir))
        assertNull(channel.install(ByteArray(0)))

        val bytes = ByteArray(64) { 3 }
        val id = channel.install(bytes)
        assertEquals(BlobStore.sha1Hex(bytes), id)
        assertTrue(channel.contains(id!!))
        assertTrue("装进来的立刻算'能画'", id in channel.available.value)
    }

    // ── 联机：谁有谁发 ──────────────────────────────────────────────────────

    @Test(timeout = 40_000)
    fun `客人向房主要图，分块过来拼好`() {
        val hostBlobs = BlobChannel(BlobStore(hostDir))
        val guestBlobs = BlobChannel(BlobStore(guestDir))
        val bytes = ByteArray(200_000) { (it * 13 % 251).toByte() }
        val id = hostBlobs.install(bytes) ?: error("素材没装进去")

        val host = TcpLink()
        val guest = TcpLink()
        assertTrue(host.register(hostBlobs))
        assertTrue(guest.register(guestBlobs))
        val port = host.host(0)
        guest.join("127.0.0.1", port)

        try {
            awaitPlayers(host, 2)
            assertTrue(guestBlobs.request(id, host.playerId))
            awaitBlob(guestBlobs, id)

            assertArrayEquals(bytes, guestBlobs.file(id)!!.readBytes())
            assertNull("收齐后进度表清空", guestBlobs.progress.value[id])
            assertTrue("收齐的也算'能画'", id in guestBlobs.available.value)
            assertEquals("ready 通知过", id, guestBlobs.ready.replayCache.last())
        } finally {
            guest.close()
            host.close()
        }
    }

    @Test(timeout = 40_000)
    fun `客人之间经房主中转直接拿图`() {
        val hostBlobs = BlobChannel(BlobStore(hostDir))
        val firstBlobs = BlobChannel(BlobStore(guestDir))
        val secondBlobs = BlobChannel(BlobStore(secondDir))
        val bytes = ByteArray(150_000) { (it * 7 % 251).toByte() }
        val id = firstBlobs.install(bytes) ?: error("素材没装进去")

        val host = TcpLink()
        val first = TcpLink()
        val second = TcpLink()
        assertTrue(host.register(hostBlobs))
        assertTrue(first.register(firstBlobs))
        assertTrue(second.register(secondBlobs))
        val port = host.host(0)
        first.join("127.0.0.1", port)
        second.join("127.0.0.1", port)

        try {
            awaitPlayers(host, 3)
            // 拥有者是另一个客人：want 和 chunk 都要经房主中转
            assertTrue(secondBlobs.request(id, first.playerId))
            awaitBlob(secondBlobs, id)

            assertArrayEquals(bytes, secondBlobs.file(id)!!.readBytes())
            assertFalse("房主自己不该存下这张图", hostBlobs.contains(id))
        } finally {
            second.close()
            first.close()
            host.close()
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun awaitPlayers(link: TcpLink, count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (link.state.value.players.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("报到没完成：${link.state.value.players}", count, link.state.value.players.size)
    }

    private fun awaitBlob(channel: BlobChannel, id: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!channel.contains(id) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("图没到，进度：${channel.progress.value}", channel.contains(id))
    }
}
