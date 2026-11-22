package com.poria.netnsd.room

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
 * 客户端 → 房主   Frame(ns="lobby", p={"type":"say","text":"开一局？"})
 * 房主 → 所有人   Frame(ns="lobby", p={"type":"snap","room":{…}})          ← 广播
 * 房主 → 某个玩家 Frame(ns="lobby", from="<房主>", p={"type":"rejected","reason":"…"})
 * ```
 *
 * 快照整份推给所有人（含发起者），客户端不做本地推演；超长时从**最老的聊天**开始丢，
 * 人和设置一条不少。
 */
object LobbyProtocol {

    /** 频道名：`TcpLink.register(LobbyChannel(...))` 用。 */
    const val NS = "lobby"

    /** 房间快照/命令的格式版本；加了不兼容的字段要动它。 */
    const val VERSION = 1

    const val MAX_NAME = 12
    const val MAX_TEXT = 300
    const val MAX_ROOM = 24
    const val MAX_ID = 32
    const val MAX_AVATAR = 64
    private const val MAX_REASON = 80

    /** 快照目标字节数：链路一行有 8KB 上限，留点余量。 */
    const val MAX_SNAPSHOT_BYTES = 6_000

    private val json get() = LinkCodec.json

    // ── 命令（客户端 → 房主）────────────────────────────────────────────────

    @Serializable
    sealed interface Command {

        @Serializable
        @SerialName("join")
        data class Join(val name: String) : Command

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

        /** 报上自己的头像 id（图片本体在 `BlobChannel` 里，谁要谁去拉）。 */
        @Serializable
        @SerialName("avatar")
        data class SetAvatar(val avatarId: String) : Command

        @Serializable
        @SerialName("start")
        data object Start : Command
    }

    // ── 事件（房主 → 客户端）────────────────────────────────────────────────

    @Serializable
    sealed interface Event {

        /** 整份权威快照。 */
        @Serializable
        @SerialName("snap")
        data class Snapshot(val room: RoomSnapshot) : Event

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
    fun encodeSnapshot(snapshot: RoomSnapshot): JsonElement {
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
