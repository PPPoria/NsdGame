package com.poria.netnsd.azul

import com.poria.game.azul.AzulSnapshot
import com.poria.game.azul.Tile
import com.poria.netnsd.link.LinkCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * `azul` 频道（`ns = "azul"`）的负载：命令 + 事件。
 *
 * 和大厅一样的分工：**命令只发给服务器，事件只由服务器发**。
 *
 * ```
 * 客户端 → 服务器   {"type":"plate","row":2,"tile":"Blue","plate":["Blue","Blue","Red"]}
 *                 {"type":"sync"}                       ← 晚进页面的人先要一份当前局面
 * 服务器 → 所有人   {"type":"snap","version":1,"game":{…}}  ← 整份局面（含发起者）
 * 服务器 → 某个人   {"type":"rejected","reason":"还没轮到你"}
 * ```
 *
 * **没有"这一轮结束 / 开下一轮"的命令**：那是服务器的节奏——
 * 每次 take 之后服务器自己看 `AzulEngine.nowCanFinishRound`，命中就 `finishRound()`，
 * 停一会儿再 `nextRoundOrFinishGame()`。客户端只管拿砖和看局面。
 *
 * 局面是 `:game-core` 的 [AzulSnapshot]——**它自己就是 `@Serializable`**，所以这里不用再抄一份 DTO；
 * 编码仍用 `LinkCodec.json`（全局唯一的 Json 实例，配置对齐）。
 *
 * 花砖没有隐藏信息：所有人的地板 / 墙 / 分数都是公开的，所以整份广播，不用 `broadcastEach`。
 */
object AzulProtocol {

    /** 频道名：`TcpLink.register(AzulChannel(...))` 用。 */
    const val NS = "azul"

    /** 协议版本；`AzulSnapshot` 的字段有不兼容改动时动它。 */
    const val VERSION = 1

    /** 4 人满台大约 2–3KB；链路一行 8KB，这里留余量做警戒线。 */
    const val MAX_SNAPSHOT_BYTES = 6_000

    private const val MAX_REASON = 80

    private val json get() = LinkCodec.json

    // ── 命令（客户端 → 服务器）──────────────────────────────────────────────

    @Serializable
    sealed interface Command {

        /** 我刚进对局页：把当前局面给我一份（服务器回一条 `snap`）。 */
        @Serializable
        @SerialName("sync")
        data object Sync : Command

        /**
         * 从工厂盘拿砖。
         *
         * [plate] 是那个盘子上的全部砖——引擎靠它认盘子（`plates.find { it.tiles == 它 }`），
         * 所以必须原样传，别只传一个 tile。
         */
        @Serializable
        @SerialName("plate")
        data class TakeFromPlate(val row: Int, val tile: Tile, val plate: List<Tile>) : Command

        /** 从中央区（首砖区 + 没人要的砖）拿砖。 */
        @Serializable
        @SerialName("redundant")
        data class TakeFromRedundant(val row: Int, val tile: Tile) : Command
    }

    // ── 事件（服务器 → 客户端）──────────────────────────────────────────────

    @Serializable
    sealed interface Event {

        /** 整份局面。 */
        @Serializable
        @SerialName("snap")
        data class Snapshot(
            val version: Int = VERSION,
            val game: AzulSnapshot,
        ) : Event

        /** 刚才那条命令没生效，给人看的原因。 */
        @Serializable
        @SerialName("rejected")
        data class Rejected(val reason: String) : Event
    }

    fun encode(command: Command): JsonElement = json.encodeToJsonElement<Command>(command)

    fun decodeCommand(payload: JsonElement?): Command? {
        if (payload == null) return null
        return runCatching { json.decodeFromJsonElement<Command>(payload) }.getOrNull()
    }

    fun encode(event: Event): JsonElement = json.encodeToJsonElement<Event>(event)

    fun decodeEvent(payload: JsonElement?): Event? {
        if (payload == null) return null
        return runCatching { json.decodeFromJsonElement<Event>(payload) }.getOrNull()
    }

    fun snapshot(game: AzulSnapshot): JsonElement = encode(Event.Snapshot(game = game))

    fun rejected(reason: String): JsonElement =
        encode(Event.Rejected(reason.trim().take(MAX_REASON)))
}
