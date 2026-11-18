package com.poria.net.proto.frame

import java.io.IOException

/**
 * 帧层协议违规。`TcpTransport` 把它映射成 `CloseReason.Protocol(message)` 后断开连接。
 *
 * 归属：传输与消息协议。这是 `:net-proto` 对 `:net-core` 暴露的**唯一**错误类型，
 * 让传输层不必知道"是 magic 错了还是 JSON 坏了"，只需要"对面不按协议说话，断开"。
 *
 * 继承 [IOException]：让调用方可以只 catch IOException 就同时兜住"网络错误 + 协议错误"。
 */
class FrameProtocolException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Kind {
        /** magic 不是 'NG'：八成是手输 IP 连到了别的服务。 */
        BAD_MAGIC,

        /** type 字节不在 [com.poria.net.proto.MsgType] 类型表内。 */
        BAD_TYPE,

        /** flags 非 0：当前协议没有定义任何标志位。 */
        UNSUPPORTED_FLAGS,

        /** 载荷超过 [FrameHeader.MAX_PAYLOAD_BYTES]，或长度字段越界（u32 高位导致负数）。 */
        PAYLOAD_TOO_LARGE,

        /** 帧头只读到一部分就 EOF。 */
        TRUNCATED_HEADER,

        /** 载荷只读到一部分就 EOF。 */
        TRUNCATED_PAYLOAD,

        /** 载荷不是合法 JSON，或缺必需字段。 */
        MALFORMED_JSON,
    }

    override fun toString(): String = "FrameProtocolException[$kind]: ${message}"
}
