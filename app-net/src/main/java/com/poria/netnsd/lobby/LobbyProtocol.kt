package com.poria.netnsd.lobby

import com.poria.netnsd.link.Frame
import com.poria.netnsd.link.LinkCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * `lobby` 频道（`ns = "lobby"`）的负载格式：命令 + 事件。
 *
 * 链路（`TcpLink`）只负责"按 ns 把 payload 交给 [LobbyChannel]"，
 * 帧里没有"这是命令还是推送"的字段——方向由"从哪条连接收到"决定：
 *
 * ```
 * 客户端 → 服务器   Frame(ns="lobby", p={"type":"say","text":"开一局？"})
 * 服务器 → 所有人   Frame(ns="lobby", p={"type":"snap","lobby":{…}})        ← 广播
 * 服务器 → 某个人   Frame(ns="lobby", from="<服务器>", p={"type":"rejected","reason":"…"})
 * ```
 *
 * ## 这个频道只认"身份 + 座位 + 聊天"，**不认名字和头像**
 *
 * 名字/头像属于**个人资料**，走 [com.poria.netnsd.profile.ProfileChannel]：
 * 它们不影响任何权威判断（座位、发言、房主权限全都只认 `playerId`）。
 * 分开的好处是资料可以晚到、可以随时更新，不用等一份新快照。
 */
object LobbyProtocol {

    /** 频道名：`TcpLink.register(LobbyChannel(...))` 用。 */
    const val NS = "lobby"

    /**
     * 房间快照/命令的格式版本。
     *
     * v2：`players` 只剩 `id + seat`，`ready` 挪到 `readyIds`，`join` 不再带昵称。
     */
    const val VERSION = 2

    const val MAX_TEXT = 300
    const val MAX_ROOM = 24
    const val MAX_ID = 32
    private const val MAX_REASON = 80

    /** 快照目标字节数：链路一行有 8KB 上限，留点余量。 */
    const val MAX_SNAPSHOT_BYTES = 6_000

    private val json get() = LinkCodec.json

    // ── 命令（客户端 → 服务器）──────────────────────────────────────────────

    @Serializable
    sealed interface Command {

        /** 进房。**只报到，不带资料**：名字和头像由 `profile` 频道负责。 */
        @Serializable
        @SerialName("join")
        data object Join : Command

        @Serializable
        @SerialName("say")
        data class Say(val text: String) : Command

        @Serializable
        @SerialName("ready")
        data class Ready(val ready: Boolean) : Command

        @Serializable
        @SerialName("seat")
        data class TakeSeat(val seat: Int) : Command

        @Serializable
        @SerialName("swap")
        data class SwapSeats(val a: Int, val b: Int) : Command

        @Serializable
        @SerialName("size")
        data class SetTableSize(val size: Int) : Command

        @Serializable
        @SerialName("game")
        data class SelectGame(val gameId: String) : Command

        @Serializable
        @SerialName("option")
        data class SetOption(val optionId: String, val on: Boolean) : Command

        @Serializable
        @SerialName("kick")
        data class Kick(val playerId: String) : Command

        @Serializable
        @SerialName("start")
        data object Start : Command
    }

    // ── 事件（服务器 → 客户端）──────────────────────────────────────────────

    @Serializable
    sealed interface Event {

        /** 整份权威快照。 */
        @Serializable
        @SerialName("snap")
        data class Snapshot(val lobby: LobbySnapshot) : Event

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

    fun rejected(reason: String): JsonElement =
        encode(Event.Rejected(reason.trim().take(MAX_REASON)))

    /**
     * 整份快照（已经按行预算裁过）。
     *
     * 超过 [MAX_SNAPSHOT_BYTES] 时从**最老的聊天**开始丢：座位/设置/人永远是完整的。
     */
    fun encodeSnapshot(snapshot: LobbySnapshot): JsonElement {
        var messages = snapshot.messages
        while (true) {
            val payload = encode(Event.Snapshot(snapshot.copy(messages = messages)))
            val line = LinkCodec.encode(Frame(ns = NS, p = payload))
            if (messages.size <= 1 || line.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) {
                return payload
            }
            messages = messages.subList(1, messages.size)
        }
    }
}
