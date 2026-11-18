package com.poria.net.proto.session

import kotlinx.serialization.Serializable

/**
 * `RESUME_REQ` 载荷（C→H，断线重连时）。**FROZEN**（归属：传输与消息协议；签名审批：架构师）。
 *
 * 属于**会话层协议**（MsgType 码 10，不随 6..9/11 的游戏载荷区间）。
 *
 * - [token] 是 `JOIN_OK.sessionToken` 的原样回传。房主校验：token 是否签发给该 `playerId`、
 *   是否过期（保座 60s，D3）、座位是否还在 `SUSPENDED`。
 * - [fromSeq] 是客端**最后一次成功应用**的事件序号。房主当前策略是**直接回全量快照**
 *   （ARCHITECTURE.md §3.4 ④：重连期间状态可能已大幅前进，全量更快且不漏），
 *   所以 [fromSeq] 目前只用于日志与诊断 —— 保留它是因为将来若要"小断层补事件"，
 *   协议不需要再改一次。
 */
@Serializable
data class ResumeReq(

    /** 重连凭证（`JOIN_OK.sessionToken` 原样回传）。 */
    val token: String,

    /** 客端最后成功应用的 `seq`（0 表示尚未应用任何事件）。 */
    val fromSeq: Long,
)
