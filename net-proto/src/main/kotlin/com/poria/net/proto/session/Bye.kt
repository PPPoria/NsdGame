package com.poria.net.proto.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 房主解散房间的原因。**FROZEN**（ADR-020；归属：传输与消息协议；签名审批：架构师）。
 *
 * 两种原因对客端的文案不同：`HOST_DISSOLVED` 是"房间已解散"，
 * `HOST_EXIT` 是"房主退出房间，房间关闭"。两者都不允许变成"与房主失去连接"——
 * 那句文案专属于**没收到 `BYE` 就断线**的心跳超时路径（ARCHITECTURE.md §3.4 ⑤）。
 */
@Serializable
enum class ByeReason {

    /** 房主解散房间（`closeRoom`，房间不再存在）。 */
    @SerialName("HOST_DISSOLVED")
    HOST_DISSOLVED,

    /** 房主退出（与解散在客端体验上是两件事，虽然本端都关闭连接）。 */
    @SerialName("HOST_EXIT")
    HOST_EXIT,
}

/**
 * `BYE` 载荷（H→C，房主主动关房，`closeRoom(HOST_EXIT)`）。**FROZEN**
 * （ADR-020 修订；归属：传输与消息协议；签名审批：架构师）。
 *
 * **这是"房主明确关房"的唯一标志。** 客端收到 [Bye] → `HOST_LOST`，文案按 [code] 区分；
 * 而没有收到 [Bye] 就断线（划掉任务卡 / 进程被杀 / 网络断）必须走心跳超时路径，
 * 文案只能是"与房主失去连接"（ARCHITECTURE.md §3.4 ⑤——这里写错会变成误导用户的 bug 报告）。
 */
@Serializable
data class Bye(

    /** 原因码。客端按它查本地文案（ADR-020）。 */
    val code: ByeReason = ByeReason.HOST_DISSOLVED,

    /** 诊断补充（`diagnostic-only`）：自由字符串，**禁止直接展示给用户**，只进日志。 */
    val detail: String? = null,
)
