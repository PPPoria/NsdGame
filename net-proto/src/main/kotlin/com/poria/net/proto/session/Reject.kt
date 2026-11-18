package com.poria.net.proto.session

import kotlinx.serialization.Serializable

/**
 * `REJECT` 的拒绝码。**FROZEN**（归属：传输与消息协议；签名审批：架构师）。
 *
 * 线缆上是 enum 名（大写蛇形），与 `PROTOCOL.md` §2.3 的表格逐字对应。
 * 新增一个码 = 改线格式 = 必须升 [com.poria.net.proto.ProtocolVersion.CURRENT]。
 *
 * 客端对每个码的文案**必须区分**（ARCHITECTURE.md §3.4 ①）：
 * 版本不匹配要引导升级，口令错要允许重输，满员/已开局只能换房间，BANNED 是终局。
 */
@Serializable
enum class RejectCode {

    /** `protoVersion` / `gameId` / `gameVersion` 与房主不一致。客端应提示升级而非重试。 */
    VERSION_MISMATCH,

    /** 座位已满（含 `SUSPENDED` 保座占用的座位，D3）。 */
    ROOM_FULL,

    /** 对局已开始且房主策略不允许中途加入（D2）。 */
    IN_PLAY,

    /** 口令错误（D6：房主明文比对，只比对不广播）。允许重输。 */
    PASSCODE_WRONG,

    /** 被房主踢出 / 在黑名单上。终局，不重试。 */
    BANNED,
}

/**
 * `REJECT` 载荷（H→C，随后断开）。**FROZEN**（ADR-020 修订；归属：传输与消息协议；签名审批：架构师）。
 *
 * [code] 是**驱动 UI** 的字段，因此是枚举（ADR-020）：客端按 `code` 查本地文案，
 * 面向用户的文案必须由 UI 按 [code] 出（ARCHITECTURE.md §3.4、角色表 §2.6 的"异常文案策略"）。
 * [detail] 是 `diagnostic-only`（自由字符串，只进日志，**禁止展示**）。
 */
@Serializable
data class Reject(

    /** 拒绝原因码，决定客端文案与是否允许重试。 */
    val code: RejectCode,

    /**
     * 诊断补充（`diagnostic-only`）：自由字符串，**禁止直接展示给用户**，只进日志/上报。
     * 它不驱动任何 UI 分支，所以允许自由字符串（ADR-020 的例外，不是漏洞）。
     */
    val detail: String? = null,
)
