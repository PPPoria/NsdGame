package com.poria.net.proto.session

import kotlinx.serialization.Serializable

/**
 * `HELLO` 载荷（C→H，连接后客端的第一条消息）。**FROZEN**（归属：传输与消息协议；签名审批：架构师）。
 *
 * 字段权威定义见 `net-proto/PROTOCOL.md` §2.1。要点：
 *
 * - **不含 `protoVersion`**：帧头 `proto` 字节已经是它（与 `EnvelopeBody` 不重复 `type` 同一原则：
 *   线格式里每个事实只出现一次，否则两处不一致时无从判断信谁）。
 * - `gameId` / `gameVersion` 由房主用来判版本兼容（§4.4：major/minor 不同即拒绝，patch 允许）。
 * - 客端在此消息里**尚未**提出加入请求；口令与昵称在 [JoinReq]。
 *
 * 会话层收到后必须校验：`gameId` 是否为本房游戏、`gameVersion` 是否兼容、`playerId` 是否被踢过。
 * 任一不过 → 回 [Reject] 并断开（**不是**在这里静默忽略）。
 */
@Serializable
data class Hello(

    /** 插件 id，如 "gomoku"。 */
    val gameId: String,

    /** 插件语义版本，如 "1.2.0"。major/minor 不同即拒绝开局（§4.4）。 */
    val gameVersion: String,

    /** 玩家身份，跨房间稳定（客端自报，房主登记）。 */
    val playerId: String,

    /** 昵称。仅展示，不参与任何判断。 */
    val nick: String,

    /** 设备型号：只写日志/房间列表的排障信息，缺失不影响任何判定（JVM 假客户端可填 "jvm"）。 */
    val deviceModel: String,
)
