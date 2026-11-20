package com.poria.netnsd.room

import com.poria.net.Peer
import java.io.FileInputStream

/**
 * 把仓库里的一份 blob 分块发给**一个** peer。
 *
 * 为什么不一次发完：`Peer.send` 是阻塞写，一条 500KB 的行会把这条连接（以及房间锁里的广播）
 * 按住几百毫秒。分块之后单次只写 ~4KB，聊天/快照最多排在这一块的后面几毫秒。
 *
 * 调用方式（`RoomSession` 就是这么用的）：收到对方的 `BLOB_REQ` 就丢进 IO 协程，
 * 每次发块前查一次 [Peer.isOpen]，对方断开就停。
 */
object BlobSender {

    /**
     * @return 是否完整发出去了（对方断开、没有这张图都会返回 `false`）
     */
    fun send(peer: Peer, store: BlobStore, id: String): Boolean {
        val file = store.file(id)
        if (file == null || !file.isFile || file.length() <= 0) {
            peer.send(RoomProtocol.blobError(id, "房主这里没有这张图"))
            return false
        }

        val size = file.length()
        if (size > RoomProtocol.MAX_BLOB_BYTES) {
            peer.send(RoomProtocol.blobError(id, "这张图太大了"))
            return false
        }

        val chunkBytes = RoomProtocol.BLOB_CHUNK_BYTES
        val total = ((size + chunkBytes - 1) / chunkBytes).toInt()
        val buffer = ByteArray(chunkBytes)

        return FileInputStream(file).use { input ->
            var index = 0
            while (index < total) {
                if (!peer.isOpen) return false
                val read = readUpTo(input, buffer)
                if (read <= 0) break
                val chunk = if (read == chunkBytes) buffer else buffer.copyOf(read)
                if (!peer.send(RoomProtocol.blobChunk(id, index, total, chunk))) return false
                index++
            }
            true
        }
    }

    /**
     * 尽量读满 [buffer]。
     *
     * 不用 `InputStream.readNBytes`：那是 API 33 才有的（minSdk 31 用不了）。
     *
     * @return 实际读到的字节数，`0` 表示文件到头了
     */
    private fun readUpTo(input: FileInputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return read
    }
}
