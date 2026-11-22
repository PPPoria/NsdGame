package com.poria.netnsd.blob

import com.poria.netnsd.link.LinkCodec
import java.io.File
import java.io.FileInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * `blob` 频道的负载（放在帧的 `p` 里，链路不解析）。
 *
 * ```
 * 要图：  {"type":"want","id":"<sha1>"}
 * 给块：  {"type":"chunk","id":"<sha1>","index":0,"total":14,"size":41000,"data":"<base64 3KB>"}
 * 没有：  {"type":"missing","id":"<sha1>"}
 * 出错：  {"type":"failed","id":"<sha1>","reason":"…"}
 * ```
 *
 * `size` 是**整张图的字节数**，有了它接收方才能算下载百分比；`total` 是块数。
 */
@Serializable
internal sealed interface BlobPayload {

    @Serializable
    @SerialName("want")
    data class Want(val id: String) : BlobPayload

    @Serializable
    @SerialName("chunk")
    data class Chunk(
        val id: String,
        val index: Int,
        val total: Int,
        /** 整张图的字节数（只用来算进度/校验，不参与拼装）。 */
        val size: Int,
        @Serializable(with = Base64Bytes::class) val data: ByteArray,
    ) : BlobPayload

    @Serializable
    @SerialName("missing")
    data class Missing(val id: String) : BlobPayload

    @Serializable
    @SerialName("failed")
    data class Failed(val id: String, val reason: String) : BlobPayload
}

/** `blob` 频道的编解码；和链路用同一套 JSON 配置。 */
internal object BlobCodec {

    private val json = LinkCodec.json

    fun encode(payload: BlobPayload): JsonElement = json.encodeToJsonElement(payload)

    fun decode(payload: JsonElement?): BlobPayload? {
        if (payload == null) return null
        return runCatching { json.decodeFromJsonElement<BlobPayload>(payload) }.getOrNull()
    }
}

/**
 * 把文件按 [BlobLimits.CHUNK_BYTES] 一块块读出来交给 [action]；[action] 返回 `false` 就停。
 *
 * 顺序**必须**一块一块读：`BlobStore` 拼装时不接受乱序块，交错的第二条流会把整份传输弄坏，
 * 所以发送方自己也串行（`BlobChannel` 用 per-(收件人, id) 的 Job 保证）。
 *
 * 不用 `InputStream.readNBytes`：那是 API 33 才有的（minSdk 31 用不了）。
 */
internal inline fun forEachChunk(
    file: File,
    action: (index: Int, total: Int, size: Int, bytes: ByteArray) -> Boolean,
) {
    val size = file.length()
    if (size <= 0L || size > BlobLimits.MAX_BYTES) return
    val total = ((size + BlobLimits.CHUNK_BYTES - 1) / BlobLimits.CHUNK_BYTES).toInt()
    val buffer = ByteArray(BlobLimits.CHUNK_BYTES)

    FileInputStream(file).use { input ->
        var index = 0
        while (index < total) {
            val read = readUpTo(input, buffer)
            if (read <= 0) return
            val chunk = buffer.copyOf(read)
            if (!action(index, total, size.toInt(), chunk)) return
            index++
        }
    }
}

private fun readUpTo(input: FileInputStream, buffer: ByteArray): Int {
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count < 0) break
        read += count
    }
    return read
}
