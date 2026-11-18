package com.poria.net.proto.frame

import com.poria.net.proto.Envelope
import java.io.InputStream

/**
 * 一条完整消息 = 一整帧（9 字节帧头 + JSON 载荷），不含额外包装。
 *
 * 与 `:net-core` 的 `com.poria.net.core.transport.Message` 是同一个底层类型（`ByteArray`），
 * 两侧只是各自的包内别名，无需任何转换。语义 = "把 [FrameCodec.encode] 的产物原样发出去"。
 */
typealias Message = ByteArray

/**
 * 帧编解码器。签名 FROZEN（角色表 §3 / ARCHITECTURE.md §12.1）。
 *
 * 归属：传输与消息协议。**签名审批：架构师**。
 *
 * 三个方法的分工：
 * - [encode]：`Envelope` → 整帧字节（含帧头）。**不做业务校验**。
 * - [readFrame]：字节流 → `Envelope`。**半包状态机在这里**（调用 [FrameIo.readRawFrame]）。
 * - [decodePayload]：按 `type`（+ 版本）把不透明的 `JsonElement` 还原成具体载荷对象。
 *   未注册的类型**原样返回 `JsonElement`** —— 这样 `:net-proto/game/` 的载荷 schema
 *   可以由游戏协议设计师随时注册进来，不需要改本文件。
 *
 * 硬边界：`Envelope.payload` 是不透明的 `JsonElement`。本层只保证"这段 JSON 完整送达"，
 * 永不解释内容，也不往 `Envelope` 上加业务字段。
 */
interface FrameCodec {

    /** `Envelope` → 一整帧字节。载荷超上限时抛 [FrameProtocolException] `PAYLOAD_TOO_LARGE`。 */
    fun encode(env: Envelope): Message

    /**
     * 读出一整帧并还原成 `Envelope`。
     *
     * @throws java.io.EOFException 流在帧边界上结束
     * @throws FrameProtocolException 帧非法（magic / type / flags / 长度 / JSON）
     */
    suspend fun readFrame(input: InputStream): Envelope

    /**
     * 按 `type` 把 `payload` 反序列化成具体类型；没有注册解码器则原样返回 `JsonElement`。
     *
     * 消费者：会话层（`:net-core/session`）。传输层从不调用它。
     */
    fun decodePayload(env: Envelope): Any
}
