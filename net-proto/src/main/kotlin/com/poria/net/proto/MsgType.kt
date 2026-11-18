package com.poria.net.proto

/**
 * 消息类型表（帧头 `type` 字节，u8）。签名 FROZEN（ARCHITECTURE.md §4.2 / §12.1）。
 *
 * 归属：传输与消息协议；**签名审批：架构师**。
 *
 * 编码区间约定（给未来的自己留退路）：
 * - `1..5`    会话握手（传输角色）
 * - `6..9`,`11` 游戏载荷（游戏协议设计师；**类型码本身归传输角色**，因为它是线格式）
 * - `10`      重连请求 `RESUME_REQ`（**会话层协议，不含游戏语义** → 传输角色所有，不随 6..11）
 * - `12..16`  心跳 / 控制（传输角色）
 * - `17..18`  大厅意图 `LOBBY_REQ` / `LOBBY_RESULT`（ADR-019，传输角色）
 *
 * 新增类型 = 改线格式 = 必须升 [ProtocolVersion]，否则老客端会把新帧当 `BAD_TYPE` 断掉。
 * 本类型表的完整变更史：`v1`（1..16）→ `v2`（+17/18，ADR-019）。
 */
enum class MsgType(val code: Int) {
    /** C→H 首个消息：protoVersion / gameId / gameVersion / playerId / nick / deviceModel。 */
    HELLO(1),

    /** H→C 握手应答：roomInstanceId / sessionId / 房间元信息。 */
    WELCOME(2),

    /** H→C 拒绝：RejectCode + reason，随后断开。 */
    REJECT(3),

    /** C→H 加入请求：passcode / nick。 */
    JOIN_REQ(4),

    /** H→C 加入成功：mySeat / sessionToken / players[] / phase。 */
    JOIN_OK(5),

    /** H→C 全量状态锚点：seq / stateHash / state / phase / players[]。 */
    SNAPSHOT(6),

    /** C→H 玩家意图（不是状态变更）。 */
    CMD(7),

    /** H→C 命令回执：cmdSeq / accepted / rejectReason。 */
    CMD_RESULT(8),

    /** H→C 增量事件批（≤16 条）。 */
    EVENT_BATCH(9),

    /** C→H 重连请求：token / fromSeq。**会话层协议**（不属于 6..11 的游戏载荷区间）。 */
    RESUME_REQ(10),

    /** H→C 漂移检测：seq / stateHash。 */
    SYNC_HASH(11),

    /** 双向心跳。 */
    PING(12),

    /** 双向心跳应答。 */
    PONG(13),

    /** H→C 踢人。 */
    KICK(14),

    /** H→C 房主解散。 */
    BYE(15),

    /** 双向协议级错误，随后断开。 */
    ERROR(16),

    /**
     * C→H 大厅意图：换座 / 退出（ADR-019）。
     *
     * 为什么不是 `CMD`：`CMD` 是**游戏**命令（`PLAYING` 期才有意义，schema 归游戏协议设计师，
     * 校验归插件）；换座发生在 `LOBBY`，让插件去校验换座是错的所有者。
     */
    LOBBY_REQ(17),

    /** H→C 大厅意图回执。**软拒绝**（`ok=false`）不连断，与 `REJECT`/`ERROR` 的致命语义严格区分。 */
    LOBBY_RESULT(18),
    ;

    companion object {
        private val byCode: Map<Int, MsgType> = entries.associateBy { it.code }

        /** 未知类型码返回 null（调用方决定是断连还是忽略）。 */
        fun fromCode(code: Int): MsgType? = byCode[code]
    }
}
