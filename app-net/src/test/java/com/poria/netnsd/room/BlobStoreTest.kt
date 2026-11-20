package com.poria.netnsd.room

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片仓库单测：分块拼装、hash 校验、上限、配额淘汰。
 *
 * 不碰 socket 也不碰 Android，`:app-net:test` 里直接跑。
 */
class BlobStoreTest {

    private val dir = Files.createTempDirectory("blob-store").toFile()
    private val store = BlobStore(dir)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun chunksOf(bytes: ByteArray): List<ByteArray> {
        val size = RoomProtocol.BLOB_CHUNK_BYTES
        val count = (bytes.size + size - 1) / size
        return (0 until count).map { index ->
            bytes.copyOfRange(index * size, minOf((index + 1) * size, bytes.size))
        }
    }

    @Test
    fun `分块收齐之前拿不到，收齐后内容一字不差`() {
        val bytes = ByteArray(7_000) { (it * 31 % 251).toByte() }
        val id = BlobStore.sha1Hex(bytes)
        val chunks = chunksOf(bytes)
        assertTrue("7000 字节应该分 3 块", chunks.size == 3)

        chunks.dropLast(1).forEachIndexed { index, data ->
            assertFalse("还没收齐", store.put(id, index, chunks.size, data))
            assertNull("没拼完不能读", store.file(id))
            assertFalse(store.contains(id))
        }

        assertTrue("最后一块到了才完成", store.put(id, chunks.lastIndex, chunks.size, chunks.last()))
        assertTrue(store.contains(id))
        assertArrayEquals(bytes, store.file(id)!!.readBytes())
    }

    @Test
    fun `hash 对不上就丢弃，不留一个坏文件`() {
        val content = ByteArray(300) { 1 }
        // 故意报一个和内容不匹配的 id
        val id = BlobStore.sha1Hex(ByteArray(300) { 2 })
        assertFalse(store.put(id, 0, 1, content))
        assertNull(store.file(id))
    }

    @Test
    fun `乱序、超长、总块数超限都会被拒`() {
        val first = ByteArray(10) { 1 }
        val second = ByteArray(10) { 2 }
        val id = BlobStore.sha1Hex(first + second)

        assertFalse("先来第二块：拒绝", store.put(id, 1, 2, second))
        assertFalse(store.contains(id))
        assertFalse("从 0 重来，还没齐", store.put(id, 0, 2, first))
        assertTrue("顺序对上了", store.put(id, 1, 2, second))
        assertArrayEquals(first + second, store.file(id)!!.readBytes())

        val another = BlobStore.sha1Hex(ByteArray(RoomProtocol.BLOB_CHUNK_BYTES + 1) { 7 })
        assertFalse(
            "一块不能超过 BLOB_CHUNK_BYTES",
            store.put(another, 0, 1, ByteArray(RoomProtocol.BLOB_CHUNK_BYTES + 1) { 7 }),
        )
        assertFalse(
            "总块数不能超过上限",
            store.put(another, 0, RoomProtocol.MAX_BLOB_CHUNKS + 1, ByteArray(10)),
        )
    }

    @Test
    fun `id 只认 sha1 十六进制，顺手挡掉路径穿越`() {
        assertTrue(BlobStore.isValidId(BlobStore.sha1Hex(byteArrayOf(1, 2, 3))))
        listOf("../etc/passwd", "ABC", "a".repeat(39), "g".repeat(40), "builtin:azul").forEach {
            assertFalse("`$it` 不该是合法 id", BlobStore.isValidId(it))
        }
        assertFalse(store.put("../evil", 0, 1, ByteArray(4)))
        assertFalse("install 也不收非法 id", store.install("../evil", ByteArray(4)))
        assertNull(store.file("../evil"))
    }

    @Test
    fun `重复上传同一份直接算完成`() {
        val bytes = ByteArray(100) { 3 }
        val id = BlobStore.sha1Hex(bytes)
        assertTrue(store.install(id, bytes))
        assertTrue("已经有了就不用再收", store.put(id, 0, 1, bytes))
    }

    @Test
    fun `install 之后能读能查`() {
        val bytes = ByteArray(200) { 5 }
        val id = BlobStore.sha1Hex(bytes)
        assertTrue(store.install(id, bytes))
        assertTrue(store.contains(id))
        val file = store.file(id)
        assertNotNull("install 之后应该能拿到文件", file)
        assertArrayEquals(bytes, file!!.readBytes())
        assertEquals(bytes.size, store.open(id)!!.use { it.readBytes().size })
    }

    @Test
    fun `配额满了淘汰最老的`() {
        val small = Files.createTempDirectory("blob-quota").toFile()
        try {
            val limited = BlobStore(small, quotaBytes = 250)
            val older = ByteArray(200) { 1 }
            val newer = ByteArray(200) { 2 }
            val olderId = BlobStore.sha1Hex(older)
            val newerId = BlobStore.sha1Hex(newer)

            assertTrue(limited.install(olderId, older))
            assertTrue(limited.install(newerId, newer))

            assertFalse("老的应该被挤出去", limited.contains(olderId))
            assertTrue("新的还在", limited.contains(newerId))
        } finally {
            small.deleteRecursively()
        }
    }

    @Test
    fun `同一张图两条流交错就会乱序——所以会话层必须按 id 去重`() {
        val bytes = ByteArray(RoomProtocol.BLOB_CHUNK_BYTES * 2) { (it % 97).toByte() }
        val id = BlobStore.sha1Hex(bytes)
        val chunks = chunksOf(bytes)
        assertTrue("这个用例需要两块", chunks.size == 2)

        // 相当于同一个 id 被要了两次、两条流交替到达
        assertFalse(store.put(id, 0, 2, chunks[0]))
        assertFalse("期待第 1 块却收到第 0 块：整份放弃", store.put(id, 0, 2, chunks[0]))
        assertFalse("这块也没用了", store.put(id, 1, 2, chunks[1]))
        assertFalse(store.contains(id))

        // 重新按顺序来一次就成功——会话层的 (peer, id) 去重就是在保证这件事
        assertFalse(store.put(id, 0, 2, chunks[0]))
        assertTrue(store.put(id, 1, 2, chunks[1]))
        assertArrayEquals(bytes, store.file(id)!!.readBytes())
    }

    @Test
    fun `重新打开仓库能认出已经落盘的图`() {
        val bytes = ByteArray(50) { 9 }
        val id = BlobStore.sha1Hex(bytes)
        assertTrue(store.install(id, bytes))

        val reopened = BlobStore(dir)
        assertTrue("磁盘上的东西不该丢", reopened.contains(id))
        assertArrayEquals(bytes, reopened.file(id)!!.readBytes())
    }
}
