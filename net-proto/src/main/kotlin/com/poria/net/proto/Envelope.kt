package com.poria.net.proto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 消息外壳。签名 FROZEN（ARCHITECTURE.md §2.4 / §12.1）。
 *
 * 归属：传输与消息协议；**签名审批：架构师**。
 *
 * 关键接缝：`payload` 是不透明的 [JsonElement]。
 * - 传输层只保证"这段 JSON **完整送达**"，永不解释内容；
 * - 游戏协议设计师只保证"这段 JSON **有意义**"。
 *
 * 两边因此可以完全并行开发，不需要开会。
 */
@Serializable
data class Envelope(
    /** 消息类型，对应帧头的 `type` 字节。 */
    val type: MsgType,
    /** 协议大版本（帧头 `proto` 字节同源）。 */
    val protoVersion: Int,
    /** 不透明载荷。 */
    val payload: JsonElement,
    /** EVENT 用：房主侧单调递增的事件序号（快照锚点）。其余消息为 0。 */
    val seq: Long = 0,
    /** CMD 用：每玩家单调递增，用于幂等去重。其余消息为 0。 */
    val cmdSeq: Long = 0,
) {

    companion object {
        /** 便捷构造：默认当前协议版本、空载荷。消费者：会话层与各测试。 */
        fun of(
            type: MsgType,
            payload: JsonElement = JsonNull,
            seq: Long = 0,
            cmdSeq: Long = 0,
            protoVersion: Int = ProtocolVersion.CURRENT,
        ): Envelope = Envelope(
            type = type,
            protoVersion = protoVersion,
            payload = payload,
            seq = seq,
            cmdSeq = cmdSeq,
        )
    }
}
