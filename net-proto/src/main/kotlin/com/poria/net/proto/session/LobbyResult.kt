package com.poria.net.proto.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `LOBBY_RESULT` 的拒绝码。**FROZEN**（ADR-020；归属：传输与消息协议；签名审批：架构师）。
 *
 * ## 为什么是枚举而不是自由字符串（ADR-020）
 *
 * 凡是**驱动 UI 表现**的字段，线缆上必须是枚举：
 *
 * 1. **i18n**：项目禁止在代码里硬编码中文（`BASE_MODULE_GUIDE.md` §8）。把中文放到线缆上等于
 *    绕过这条 lint，却产生同样的缺陷——将来做多语言要改协议。
 * 2. **契约稳定性**：文案会改，枚举不会。文案上线缆 = 每次改措辞都动协议版本。
 * 3. **可测试性**：断言 `code == SEAT_OCCUPIED` 是断言语义；断言一句中文会在下次改措辞时假红，
 *    比不测更糟——它训练人无视测试。
 * 4. **安全面**：服务端字符串直接进 UI，长度/换行/表情/恶意内容全得 UI 处理。
 * 5. **可观测性**：`code` 能聚合指标，自由字符串不能。
 *
 * 文案由 UI 按 code 查本地字符串资源（`strings.xml`），多语言因此是自动的。
 *
 * **新增一个码 = 改线格式**，需要升 `protoVersion`（同 `RejectCode` / `MsgType` 的纪律）。
 */
@Serializable
enum class LobbyRejectCode {

    /** `seat` 不在 `0 until maxPlayers` 内（含 `ChangeSeat` 却没带 seat 的情况）。 */
    @SerialName("SEAT_OUT_OF_RANGE")
    SEAT_OUT_OF_RANGE,

    /** 目标座位已被别人占据。 */
    @SerialName("SEAT_OCCUPIED")
    SEAT_OCCUPIED,

    /** 当前不在 `RoomPhase.LOBBY`（D2：开局后座位冻结，换座/退出都不再允许）。 */
    @SerialName("NOT_IN_LOBBY")
    NOT_IN_LOBBY,

    /** 房主不认识这个 `op`（版本不一致或对面实现有误）。 */
    @SerialName("OP_UNSUPPORTED")
    OP_UNSUPPORTED,
}

/**
 * `LOBBY_RESULT` 载荷（H→C）。**FROZEN**（ADR-019 / ADR-020；归属：传输与消息协议；签名审批：架构师）。
 *
 * ## 软拒绝 vs 致命拒绝（ADR-018 的硬约束）
 *
 * 本消息是**软拒绝**载体：`ok = false` **不断连**，房间继续存在，客端可立刻改选别的座位。
 *
 * | 消息 | 语义 | 连接 |
 * |---|---|---|
 * | [LobbyResult] `ok=false` | "这个座位被占了" / "开局了不能换" | **保持** |
 * | [Reject] | 房间都不让你进（版本/满员/口令/黑名单） | 断开 |
 * | [ProtocolError] | 我看不懂你说的话 | 断开 |
 *
 * ## UI 侧义务（留给交互角色，这里只记录约束）
 *
 * - 收到 `ok=false` 时按 [code] **查本地字符串**展示，不要拼自由文本；
 * - 客端在收到新的 `SNAPSHOT` 之后必须以快照为准重绘（名册的权威来源永远是
 *   `SNAPSHOT.players`，不是本消息的本地乐观结果）。
 *
 * 否则用户会为同一个被占的座位狂点。
 */
@Serializable
data class LobbyResult(

    /** 回显对应 [LobbyReq.reqSeq]，用于关联本次点击。 */
    val reqSeq: Int,

    /** 回显操作类型（客端可能并发发起多个请求）。 */
    val op: LobbyOp,

    /** 是否被接受。`false` 时连接**保持**。 */
    val ok: Boolean,

    /**
     * 拒绝码。**`ok = false` 时必填，`ok = true` 时为 null。**
     * UI 按它查本地文案（ADR-020：驱动 UI 的字段必须是枚举）。
     */
    val code: LobbyRejectCode? = null,

    /**
     * 诊断补充（`diagnostic-only`）：可以自由字符串，**禁止直接展示给用户**，
     * 只进日志/上报。这不是漏洞——它不驱动任何 UI 分支，所以不影响互操作。
     */
    val detail: String? = null,
)
