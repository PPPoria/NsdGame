package com.poria.net.proto.session

import com.poria.net.proto.MsgType
import com.poria.net.proto.ProtocolJson
import com.poria.net.proto.frame.JsonFrameCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * 会话层载荷的**注册表**：`MsgType → 载荷序列化器`。**FROZEN**（归属：传输与消息协议；签名审批：架构师）。
 *
 * 存在的理由：`FrameCodec.decodePayload` 需要知道"这个 `type` 的载荷应该是哪个类"，
 * 但它不该硬编码任何具体载荷（否则每加一个消息都要改帧层）。于是帧层开一个注册表口子，
 * 各归属方把自己的载荷注册进来：
 *
 * - **本表**：会话/握手载荷（`HELLO`..`RESUME_REQ`、`KICK`/`BYE`/`ERROR`、`LOBBY_REQ`/`LOBBY_RESULT`），
 *   传输角色所有；
 * - **游戏载荷**（`CMD` / `CMD_RESULT` / `EVENT_BATCH` / `SNAPSHOT` / `SYNC_HASH`）
 *   由游戏数据流/协议设计师在 `:net-proto/game/` 里注册，本表不碰。
 *
 * 未注册的类型 → `decodePayload` 原样返回 `JsonElement`（帧层照样能把消息安全地搬过去，
 * 只是不解包），这正是"传输角色与游戏协议设计师可以并行"的机制。
 *
 * ## PING / PONG 为什么不在表里
 *
 * 它们**没有载荷**（线缆上是 `payload: null`，由 `EnvelopeBody` 的 `JsonElement` 直接表达），
 * 没有任何字段需要解包。`decodePayload` 对它们返回 `JsonNull`，这是完整的答案，
 * 不是"没实现"——注册一个空类反而会让人以为里面该有东西。
 */
object SessionPayloads {

    /** 会话载荷解码表。消费者：`JsonFrameCodec.decodePayload`（经 [codec] 注入）。 */
    val decoders: Map<MsgType, KSerializer<out Any>> = mapOf(
        MsgType.HELLO to Hello.serializer(),
        MsgType.WELCOME to Welcome.serializer(),
        MsgType.REJECT to Reject.serializer(),
        MsgType.JOIN_REQ to JoinReq.serializer(),
        MsgType.JOIN_OK to JoinOk.serializer(),
        MsgType.RESUME_REQ to ResumeReq.serializer(),
        MsgType.KICK to Kick.serializer(),
        MsgType.BYE to Bye.serializer(),
        MsgType.ERROR to ProtocolError.serializer(),
        MsgType.LOBBY_REQ to LobbyReq.serializer(),
        MsgType.LOBBY_RESULT to LobbyResult.serializer(),
    )

    /**
     * 造一个**已挂上会话载荷解码器**的帧编解码器。消费者：会话层（`:net-core/session`）、测试。
     *
     * 会话层自己也持有游戏载荷（`game/`），届时用
     * `JsonFrameCodec(payloadDecoders = SessionPayloads.decoders + GamePayloads.decoders)` 合并即可
     * （`+` 冲突时右侧覆盖，别在两边注册同一个 `MsgType`）。
     */
    fun codec(json: Json = ProtocolJson.instance): JsonFrameCodec =
        JsonFrameCodec(json = json, payloadDecoders = decoders)
}
