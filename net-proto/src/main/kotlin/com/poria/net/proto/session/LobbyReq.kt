package com.poria.net.proto.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 大厅操作类型。**FROZEN**（ADR-019；归属：传输与消息协议；签名审批：架构师）。
 *
 * 只覆盖**大厅期**能改变"谁坐哪"的两个动作。设置变更（棋盘大小、先手…）不在这里：
 * 那些由房主权威直接改，改完广播 `SNAPSHOT`（ADR-017），客端不需要请求。
 *
 * 线缆值是 [SerialName] 里的原样字面量，**不是** Kotlin 常量名——重命名常量不会改线格式。
 */
@Serializable
enum class LobbyOp {

    /** 换座：把**自己**挪到 [LobbyReq.seat]。房间满员不涉及；座位被占 → `LOBBY_RESULT(ok=false)`。 */
    @SerialName("ChangeSeat")
    CHANGE_SEAT,

    /** 主动退出大厅（保留 `sessionToken`，之后可凭它 `RESUME_REQ` 回到原座位）。 */
    @SerialName("Leave")
    LEAVE,
}

/**
 * `LOBBY_REQ` 载荷（C→H）。**FROZEN**（ADR-019；归属：传输与消息协议；签名审批：架构师）。
 *
 * ## 为什么需要它（ADR-019 的理由）
 *
 * 五子棋 0 号位 = 执黑 = 先手。两人都想执黑时，在没有本消息之前唯一的手段是"退出重进"，
 * 而重进会**重新分配座位**——等于没解。
 *
 * ## 为什么用 `reqSeq` 而不是 `cmdSeq`
 *
 * [reqSeq] 只做**请求/回执关联**（客端要能知道"这条 `LOBBY_RESULT` 对应哪次点击"），
 * **不做幂等去重**：`ChangeSeat` 与 `Leave` 天然幂等（重复执行结果相同）。
 * 这反过来证明 `CMD` 的 `cmdSeq` 只对**非幂等**命令必要——两套序号各管一件事。
 *
 * ## 不变量（协议不强制，由会话层校验）
 *
 * - `op = ChangeSeat` 时 [seat] 必须非 null 且落在 `0 until maxPlayers`；
 * - `op = Leave` 时 [seat] 必须为 null（忽略）；
 * - 只在 `RoomPhase.LOBBY` 期合法。`PLAYING` 期换座 → `LOBBY_RESULT(ok=false)`（D2 座位已冻结）。
 */
@Serializable
data class LobbyReq(

    /** 请求序号（客端自增，仅用于与 `LOBBY_RESULT` 关联；回绕不处理）。 */
    val reqSeq: Int,

    /** 操作类型。 */
    val op: LobbyOp,

    /** 目标座位号（裸 Int，`Seat` 不上线缆）。仅 `ChangeSeat` 时有意义。 */
    val seat: Int? = null,
)
