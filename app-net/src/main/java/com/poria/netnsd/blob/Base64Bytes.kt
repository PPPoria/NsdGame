package com.poria.netnsd.blob

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 二进制在 JSON 里怎么走：**base64 字符串**。
 *
 * 自己写一个 serializer 而不是到处手搓 `Base64.getEncoder()`，好处是模型里可以直接放
 * `ByteArray`，编解码只有一处；base64 的字符表 `A-Za-z0-9+/=` 在 JSON 里不需要再转义，
 * 代价是 **+33%**（3KB 二进制 → 4KB 的 base64，一行留得下）。
 */
internal object Base64Bytes : KSerializer<ByteArray> {

    override val descriptor =
        PrimitiveSerialDescriptor("com.poria.netnsd.blob.Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}
