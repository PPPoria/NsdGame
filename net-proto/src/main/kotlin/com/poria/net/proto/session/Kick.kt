package com.poria.net.proto.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 被踢的原因。**FROZEN**（ADR-020；归属：传输与消息协议；签名审批：架构师）。
 *
 * 驱动 UI 的字段用枚举（ADR-020）：客端按它查本地文案，不解释线缆上的自由字符串。
 * 新增一个值 = 改线格式 = 升 `protoVersion`。
 */
@Serializable
enum class KickReason {

    /** 房主主动踢出（当前唯一原因：踢人 UI 只有一个入口）。 */
    @SerialName("BY_HOST")
    BY_HOST,
}

/**
 * `KICK` 载荷（H→C，房主把某个客端踢出去，随后断开）。**FROZEN**
 * （ADR-020 修订；归属：传输与消息协议；签名审批：架构师）。
 *
 * 与 [Bye] 的区别（客端文案必须区分，ARCHITECTURE.md §3.3 / §3.4 ⑤）：
 * - [Kick] = 你被踢了，房间还在，别人还在玩；
 * - [Bye] = 房主解散了整个房间。
 */
@Serializable
data class Kick(

    /** 原因码。客端按它查本地文案（ADR-020）。 */
    val code: KickReason = KickReason.BY_HOST,

    /** 诊断补充（`diagnostic-only`）：自由字符串，**禁止直接展示给用户**，只进日志。 */
    val detail: String? = null,
)
