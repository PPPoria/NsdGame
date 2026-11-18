package com.poria.net.proto.room

import kotlinx.serialization.Serializable

/**
 * 名册条目：`JOIN_OK.players[]` 与 `SNAPSHOT` 的玩家投影。**FROZEN**（归属：架构师）。
 *
 * ## 为什么不放在 `:game-api`
 *
 * 领域类型 `Seat` / `Seats` / `PlayerMeta` 属于 `:game-api`（游戏规则要用它们），
 * 而 `:net-proto` 按 R1 不能依赖 `:game-api`。于是必须区分两件事：
 *
 * - **[RoomPlayer] 是线缆投影（wire projection）**：座位号 + 身份 + 昵称 + 在线状态。
 *   零游戏语义，任何游戏都一样，外部实现（未来的 iOS/Web 客户端）也需要它。
 *   => 归 `:net-proto`，由架构师所有。
 * - **`PlayerMeta` / `Seat` 是领域模型**：带规则含义（谁该行动、谁被托管）。
 *   => 留在 `:game-api`，由游戏协议设计师所有。
 *
 * 两者由**会话层在一处做映射**（`HostSession` 组装名册时、`GuestSession` 消费时），
 * 绝不在 proto 侧引用领域类型，也绝不在游戏插件里引用 `RoomPlayer`。
 *
 * ## 为什么不是 `List<JsonElement>`
 *
 * 曾有一个提议：`JOIN_OK.players: List<JsonElement>`，把 schema 交给游戏协议设计师，
 * proto 只搬运。**已否决（ADR-015）**，理由是 `net-proto/PROTOCOL.md` 存在的全部意义
 * 就在于「一个不读 Kotlin 的实现者只靠这份文档就能互通」；一个 opaque 的数组把
 * 不完整性搬进了线缆，PROTOCOL.md 只能写「服务器返回的东西你们自己猜」。
 * 未来做 Web 客户端时，这个决定会以「两周对不齐字段」的形式收账。
 *
 * `JsonElement` 只允许出现在**游戏载荷**（`CMD` / `CMD_RESULT` / `EVENT_BATCH` /
 * `SNAPSHOT.state`），因为那些 schema 本来就要跟着规则演进——这是有意的接缝，不是遗漏。
 */
@Serializable
data class RoomPlayer(

    /**
     * 座位号，`0 until maxPlayers`。**冻结于开局**（D2），`Playing` 期不变。
     *
     * 注意：线缆上只用裸 Int；`Seat`（`:game-api` 的领域类型）由会话层映射，不上线缆。
     */
    val seat: Int,

    /**
     * 玩家身份，跨房间稳定（存 `DSManager`）。
     *
     * 刻意用 `String` 而不是 `@JvmInline value class PlayerId`：后者会让 JSON 序列化、
     * `Map` 键、以及 `:game-api` 侧的类型转换都变重，而本项目的规模吃不到这个收益。
     */
    val playerId: String,

    /** 昵称，仅展示。 */
    val nick: String,

    /** 是否房主。UI 用来打「房主」标签；不参与权限判断（权限只看连接身份）。 */
    val host: Boolean = false,

    /**
     * 当前是否在线。
     *
     * `false` 对应房主的 `SUSPENDED`（保座中，D3）与 `DROPPED`。注意**座位照样占用**：
     * `online=false` + `seat` 仍然有效，这正是「保座 60s」在线缆上的表达。
     */
    val online: Boolean = true,
)
