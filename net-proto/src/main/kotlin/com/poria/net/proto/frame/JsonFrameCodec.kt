package com.poria.net.proto.frame

import com.poria.net.proto.Envelope
import com.poria.net.proto.MsgType
import com.poria.net.proto.ProtocolJson
import java.io.InputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 帧体的 JSON 形态：`{"seq":0,"cmdSeq":0,"payload":{...}}`。
 *
 * `type` 与 `protoVersion` 已经在 9 字节帧头里，**不在 JSON 里重复**（省字节，也避免两处不一致）。
 * 归属：传输与消息协议；`internal`，不对外暴露（不构成公开 API）。
 */
@Serializable
internal data class EnvelopeBody(
    val seq: Long = 0,
    val cmdSeq: Long = 0,
    val payload: JsonElement,
)

/**
 * 默认帧编解码实现（JSON 载荷 + 9 字节定长头）。
 *
 * 归属：传输与消息协议。
 *
 * @param json 协议专用 Json 配置，默认 [ProtocolJson.instance]（`ignoreUnknownKeys = true`）。
 * @param payloadDecoders `MsgType → 载荷序列化器` 注册表。`:net-proto/session/` 与
 *        `:net-proto/game/` 的载荷各自注册，互不阻塞；未注册的类型 [decodePayload] 原样返回 `JsonElement`。
 */
class JsonFrameCodec(
    private val json: Json = ProtocolJson.instance,
    private val payloadDecoders: Map<MsgType, KSerializer<out Any>> = emptyMap(),
) : FrameCodec {

    override fun encode(env: Envelope): Message {
        val body = json
            .encodeToString(EnvelopeBody.serializer(), EnvelopeBody(env.seq, env.cmdSeq, env.payload))
            .toByteArray(Charsets.UTF_8)

        if (body.size > FrameHeader.MAX_PAYLOAD_BYTES) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.PAYLOAD_TOO_LARGE,
                "载荷 ${body.size} 字节超出上限 ${FrameHeader.MAX_PAYLOAD_BYTES}（type=${env.type}）",
            )
        }

        return FrameIo.frameOf(
            header = FrameHeader(
                protoVersion = env.protoVersion,
                typeCode = env.type.code,
                flags = FrameHeader.FLAGS_NONE,
                payloadLength = body.size,
            ),
            body = body,
        )
    }

    override suspend fun readFrame(input: InputStream): Envelope {
        val frame = FrameIo.readRawFrame(input)
        val header = FrameIo.headerOf(frame)
        val body = FrameIo.bodyOf(frame)

        val parsed = try {
            json.decodeFromString(EnvelopeBody.serializer(), body.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.MALFORMED_JSON,
                "载荷不是合法帧体 JSON（type=${header.typeCode}）：${e.message}",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.MALFORMED_JSON,
                "载荷 JSON 解析失败（type=${header.typeCode}）：${e.message}",
                e,
            )
        }

        // readRawFrame 已保证 typeCode 在类型表内；这里再取一次以拿到枚举值。
        val type = MsgType.fromCode(header.typeCode)
            ?: throw FrameProtocolException(
                FrameProtocolException.Kind.BAD_TYPE,
                "未知消息类型码 ${header.typeCode}",
            )

        return Envelope(
            type = type,
            protoVersion = header.protoVersion,
            payload = parsed.payload,
            seq = parsed.seq,
            cmdSeq = parsed.cmdSeq,
        )
    }

    override fun decodePayload(env: Envelope): Any {
        val serializer = payloadDecoders[env.type] ?: return env.payload
        @Suppress("UNCHECKED_CAST")
        return json.decodeFromJsonElement(serializer as KSerializer<Any>, env.payload)
    }
}
