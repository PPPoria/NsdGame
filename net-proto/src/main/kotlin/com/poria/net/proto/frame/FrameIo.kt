package com.poria.net.proto.frame

import com.poria.net.proto.MsgType
import java.io.EOFException
import java.io.InputStream

/**
 * 帧层 IO：把"帧"从字节流里切出来（半包状态机），并做全部长度/表头校验。
 *
 * 归属：传输与消息协议。
 *
 * **唯一允许的消费者**（角色表 §6 R5）：
 * - `:net-proto` 内部的 [JsonFrameCodec]（编码/解码用 internal 的三个辅助函数）；
 * - `:net-core/transport` 只调用 [readRawFrame] —— 它只切帧、**不解析 JSON**（"只搬不解析"）。
 *
 * ⚠️ **会话层（`net-core/session/`）不得引用本类**：解码的唯一入口必须是
 * [FrameCodec.readFrame]，否则将来加压缩 / 分片时会出现第二条解码路径，绕过 flags 与
 * 长度上限的严格校验。R5 是机器可查的（grep `frame.(FrameIo|FrameHeader)`）。
 *
 * 为了让这条边界**机械成立**而不只靠自觉：本文三类辅助函数（[headerOf] / [bodyOf] /
 * [frameOf]）都是 `internal`，跨模块只暴露 [readRawFrame] 一个出口。
 */
object FrameIo {

    /**
     * 从流中读出一整帧（帧头 + 载荷），返回**原始帧字节**。
     *
     * 语义要点：
     * - 永远返回完整一帧，绝不返回半包（半包责任留在本层，见 ADR-001）；
     * - 流在帧边界上正常结束 → [EOFException]（对端正常关闭）；
     * - 帧头/载荷读到一半 EOF → [FrameProtocolException] `TRUNCATED_*`（异常断开）；
     * - 表头错乱、type 未知、flags 非 0、长度超限 → [FrameProtocolException]，立即拒绝、不读载荷。
     *
     * 消费者：**只给 `:net-core/transport` 的读循环用**。会话层请用 [FrameCodec.readFrame]（R5）。
     *
     * @param input 阻塞输入流；本函数是阻塞的，调用方负责放到 IO 调度器上。
     */
    suspend fun readRawFrame(input: InputStream): Message {
        val headerBytes = ByteArray(FrameHeader.SIZE)
        val headerRead = readAtMost(input, headerBytes, 0, FrameHeader.SIZE)
        if (headerRead == 0) {
            throw EOFException("流在帧边界处结束")
        }
        if (headerRead < FrameHeader.SIZE) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.TRUNCATED_HEADER,
                "帧头只读到 $headerRead/${FrameHeader.SIZE} 字节就遇到流结束",
            )
        }

        val header = FrameHeader.parse(headerBytes)
        validateHeader(header)

        val body = ByteArray(header.payloadLength)
        val bodyRead = readAtMost(input, body, 0, header.payloadLength)
        if (bodyRead < header.payloadLength) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.TRUNCATED_PAYLOAD,
                "载荷只读到 $bodyRead/${header.payloadLength} 字节就遇到流结束",
            )
        }

        val frame = ByteArray(FrameHeader.SIZE + header.payloadLength)
        headerBytes.copyInto(frame, 0)
        body.copyInto(frame, FrameHeader.SIZE)
        return frame
    }

    /** 取一整帧的帧头（不校验载荷内容）。`internal`：跨模块不暴露（R5）。 */
    internal fun headerOf(frame: Message): FrameHeader = FrameHeader.parse(frame, 0)

    /** 取一整帧的载荷字节（不含帧头）。`internal`：跨模块不暴露（R5）。 */
    internal fun bodyOf(frame: Message): ByteArray {
        val header = headerOf(frame)
        require(frame.size == FrameHeader.SIZE + header.payloadLength) {
            "帧长度与帧头不一致：frame=${frame.size}，header 声明 ${FrameHeader.SIZE + header.payloadLength}"
        }
        return frame.copyOfRange(FrameHeader.SIZE, frame.size)
    }

    /** 把帧头与载荷拼成一整帧。`internal`：跨模块不暴露（R5）。 */
    internal fun frameOf(header: FrameHeader, body: ByteArray): Message {
        if (header.payloadLength != body.size) {
            throw IllegalArgumentException("帧头声明 ${header.payloadLength} 字节，实际载荷 ${body.size} 字节")
        }
        return header.toByteArray() + body
    }

    private fun validateHeader(header: FrameHeader) {
        if (header.flags != FrameHeader.FLAGS_NONE) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.UNSUPPORTED_FLAGS,
                "未定义的 flags=0x%02X（当前协议只允许 0）".format(header.flags),
            )
        }
        if (MsgType.fromCode(header.typeCode) == null) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.BAD_TYPE,
                "未知消息类型码 ${header.typeCode}",
            )
        }
        // u32 读进 Int 后若为负，说明对端在长度字段高位填了东西：一律按超限处理。
        if (header.payloadLength < 0 || header.payloadLength > FrameHeader.MAX_PAYLOAD_BYTES) {
            throw FrameProtocolException(
                FrameProtocolException.Kind.PAYLOAD_TOO_LARGE,
                "载荷长度 ${header.payloadLength} 超出上限 ${FrameHeader.MAX_PAYLOAD_BYTES}",
            )
        }
    }

    /**
     * 尽量读满 [length] 字节，返回实际读到的字节数（仅在流结束时小于 length）。
     * 允许底层流按任意大小分片返回（半包）。
     */
    private fun readAtMost(input: InputStream, dst: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var total = 0
        while (total < length) {
            val n = input.read(dst, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
