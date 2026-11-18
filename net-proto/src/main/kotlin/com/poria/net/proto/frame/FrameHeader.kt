package com.poria.net.proto.frame

/**
 * 帧头（9 字节定长，全部大端）。决策见 docs/ARCHITECTURE.md §4.1（ADR-006）与 `net-proto/PROTOCOL.md`。
 *
 * ```
 *  0     1     2       3      4       5   6   7   8
 * +-----+-----+-------+------+-------+-------------------+
 * | 'N' | 'G' | proto | type | flags | payloadLen : u32  |
 * +-----+-----+-------+------+-------+-------------------+
 *  └─ magic 2B ─┘      └──────────── 7B ─────────────┘
 * ```
 *
 * 归属：传输与消息协议（owner 与签名审批见角色表 §3；FROZEN）。
 * 注意：本类**只描述帧头**，不解释载荷内容 —— 载荷是 JSON 字节，语义归游戏协议设计师。
 */
data class FrameHeader(
    /** 协议大版本，取 [com.poria.net.proto.ProtocolVersion.CURRENT]；不匹配由会话层回 REJECT。 */
    val protoVersion: Int,
    /** 消息类型码，见 [com.poria.net.proto.MsgType]。 */
    val typeCode: Int,
    /** 预留位（压缩/加密/分片），当前必须是 [FLAGS_NONE]。 */
    val flags: Int,
    /** 载荷字节数（不含帧头），上限 [MAX_PAYLOAD_BYTES]。 */
    val payloadLength: Int,
) {

    /** 序列化为 9 字节帧头。 */
    fun toByteArray(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = MAGIC_N
        out[1] = MAGIC_G
        out[2] = protoVersion.toByte()
        out[3] = typeCode.toByte()
        out[4] = flags.toByte()
        out[5] = (payloadLength ushr 24).toByte()
        out[6] = (payloadLength ushr 16).toByte()
        out[7] = (payloadLength ushr 8).toByte()
        out[8] = payloadLength.toByte()
        return out
    }

    companion object {
        /** 帧头长度：magic 2 + proto 1 + type 1 + flags 1 + payloadLen 4。 */
        const val SIZE: Int = 9

        /** magic 第一个字节 'N'。 */
        const val MAGIC_N: Byte = 0x4E

        /** magic 第二个字节 'G'。 */
        const val MAGIC_G: Byte = 0x47

        /** 无任何标志位（当前唯一合法值）。 */
        const val FLAGS_NONE: Int = 0

        /** 单帧载荷上限 256 KiB。超限即 `CloseReason.Protocol` 并断开，防单包打爆 6 个连接的内存。 */
        const val MAX_PAYLOAD_BYTES: Int = 256 * 1024

        /** magic 原始字节，供"连上了但对面不是本协议"的快速判定使用。 */
        val MAGIC: ByteArray = byteArrayOf(MAGIC_N, MAGIC_G)

        /**
         * 解析帧头。只校验 magic（"表头 1 字节错乱立即拒绝"），
         * flags / type / 长度上限由 [FrameIo.readRawFrame] 与具体解码器负责。
         *
         * @throws FrameProtocolException magic 不匹配
         */
        fun parse(bytes: ByteArray, offset: Int = 0): FrameHeader {
            requireRemaining(bytes, offset, SIZE)
            if (bytes[offset] != MAGIC_N || bytes[offset + 1] != MAGIC_G) {
                throw FrameProtocolException(
                    FrameProtocolException.Kind.BAD_MAGIC,
                    "帧头 magic 不匹配：期望 0x4E47('NG')，实际 0x%02X%02X".format(
                        bytes[offset].toInt() and 0xFF,
                        bytes[offset + 1].toInt() and 0xFF,
                    ),
                )
            }
            val proto = bytes[offset + 2].toInt() and 0xFF
            val type = bytes[offset + 3].toInt() and 0xFF
            val flags = bytes[offset + 4].toInt() and 0xFF
            val len = ((bytes[offset + 5].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 8) or
                (bytes[offset + 8].toInt() and 0xFF)
            return FrameHeader(protoVersion = proto, typeCode = type, flags = flags, payloadLength = len)
        }

        private fun requireRemaining(bytes: ByteArray, offset: Int, needed: Int) {
            if (offset < 0 || bytes.size - offset < needed) {
                throw FrameProtocolException(
                    FrameProtocolException.Kind.TRUNCATED_HEADER,
                    "帧头不足 $SIZE 字节（offset=$offset, size=${bytes.size}）",
                )
            }
        }
    }
}
