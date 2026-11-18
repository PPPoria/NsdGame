package com.poria.net.proto.room

import kotlinx.serialization.Serializable

/**
 * 房主的一个可达地址候选。**FROZEN**（归属：架构师）。
 *
 * 为什么是列表而不是单个 `host:port`：
 * - Android 上 `NsdManager.resolveService` 返回的 `host` 在不少机型/路由器上是 `.local`
 *   主机名或不可直接使用（ARCHITECTURE.md §7 风险 2）；
 * - 手机会同时存在 Wi-Fi / 蜂窝 / VPN / 热点多张网卡（风险 7）。
 *
 * 因此房主在 TXT 里冗余广播**所有非回环 IPv4**，客端逐个尝试（每候选 ~1.5s 超时）。
 * 不要试图在房主侧"猜哪张是 Wi-Fi 网卡"——猜错的比例远高于让客端逐个试的成本。
 */
@Serializable
data class HostAddress(
    val ip: String,
    val port: Int,
)

/**
 * 房间的可发现元信息。**FROZEN**（ARCHITECTURE.md §2.2 / §12.1；归属：架构师）。
 *
 * 同一个类型服务三条路径，这是刻意的：
 * 1. **NSD 广播**：序列化进 TXT 记录（`proto=1, game=gomoku, phase=Lobby, ip=..., port=...`）；
 * 2. **`WELCOME` 载荷**（`roomMeta`）：握手时把权威版本发给客端，避免两端各有一套元模型；
 * 3. **手动 IP / 二维码加入**：客端自造一个最小 descriptor，走同一条连接逻辑。
 *
 * 三处共用 => 房间列表看到的阶段与实际加入时的阶段不可能不一致。
 *
 * 注意：[addresses] 在 `WELCOME` 里可能为空（对方已经连上来了，地址没有意义），
 * 用 [withoutAddresses] 生成即可。**不要**为此新建一个近乎重复的类型。
 */
@Serializable
data class RoomDescriptor(

    /** 协议大版本，与帧头同源。不同则不可连（§4.4）。 */
    val protoVersion: Int,

    /** 插件 id，如 "gomoku"。 */
    val gameId: String,

    /** 插件语义版本（major.minor 不同即拒绝开局）。 */
    val gameVersion: String,

    /** "这一次建房" 的 UUID。客端用它区分「同一房间重连」与「房主重新建房」。 */
    val roomInstanceId: String,

    /** 房间显示名。 */
    val roomName: String,

    /** 房主昵称（UI 用，不参与任何判断）。 */
    val hostNick: String,

    /** 当前在座人数。 */
    val playerCount: Int,

    /** 座位上限（D2：2~6）。 */
    val maxPlayers: Int,

    /** 房间阶段，见 [RoomPhase]。TXT 与 JSON 都用 [RoomPhase.wireValue]。 */
    val phase: RoomPhase,

    /** 是否设置了口令（**只广播有无，绝不广播口令本身**，D6）。 */
    val hasPasscode: Boolean,

    /** 多网卡候选地址；`WELCOME` 里可为空。 */
    val addresses: List<HostAddress> = emptyList(),
) {

    /** 去掉地址候选的副本：用于 `WELCOME` 载荷。 */
    fun withoutAddresses(): RoomDescriptor = copy(addresses = emptyList())
}
