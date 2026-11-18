package com.poria.net.proto.session

import kotlinx.serialization.Serializable

/**
 * `JOIN_OK` 载荷（H→C，加入成功，连接进入 ACTIVE）。**FROZEN**
 * （ADR-018 修订；归属：传输与消息协议；签名审批：架构师）。
 *
 * ## 为什么只有两个字段（ADR-018）
 *
 * `JOIN_OK` 是**一刻**，名册是**持续**。把名册塞进一刻消息，UI 必然按一次性渲染 ——
 * 之后的换座、掉线、被踢全部漏掉，这正是"UI 持有第二份权威状态"最常见的成因。
 * 所以 `players[]` / `room` 已**移出**本消息：
 *
 * > **权威状态只有一个来源 = `SNAPSHOT`。** 房主在同一次状态读取之后紧接着发 `SNAPSHOT`，
 * > 两帧因此不可能互相矛盾（等价于"第三次握手同时携带数据"）。
 *
 * 留下的两个字段都是**私有**的，无法由 `SNAPSHOT` 广播承载：
 *
 * | 字段 | 为什么必须留 |
 * |---|---|
 * | [sessionToken] | 私有凭证。挤进广播 `SNAPSHOT` = 把每个人的重连凭证发给所有人，直接毁掉 D3 保座 |
 * | [mySeat] | 私有、零歧义；若靠 `playerId` 去 `players[]` 里找自己，会被"房主规范化昵称/playerId"这类边界静默打破（症状："为什么我不在名册里"） |
 *
 * ## `mySeat` 是**冗余副本**（`not-authoritative`）
 *
 * 权威座位号永远是 `SNAPSHOT.players[]` 里 `playerId == 我` 那一条的 `seat`。
 * 本字段只用于"刚加入时立刻知道该往哪张椅子坐下"（此时快照可能还在路上）。
 * 两者由房主同一次状态读取产生，正常情况下必然一致；若不一致，**以 `SNAPSHOT` 为准**。
 */
@Serializable
data class JoinOk(

    /** 重连凭证（字符串形态）。客端存起来，断线后用于 `RESUME_REQ`。**绝不进广播。** */
    val sessionToken: String,

    /** 分配给我的座位号（裸 Int）。`not-authoritative`：权威来源是 `SNAPSHOT.players[].seat`。 */
    val mySeat: Int,
)
