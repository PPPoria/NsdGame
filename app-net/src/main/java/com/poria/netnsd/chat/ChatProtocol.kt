package com.poria.netnsd.chat

/** 客户端 → 服务端的命令。 */
sealed interface ChatCommand {
    data class Join(val name: String) : ChatCommand
    data class Say(val text: String) : ChatCommand
}

/** 服务端 → 客户端的事件。 */
sealed interface ChatEvent {
    /** 进房成功：分到的座位 + 房间名（第一条一定是它）。 */
    data class Welcome(val seat: Int, val roomName: String) : ChatEvent

    /** 有人进来了（包括自己，所以客户端只靠这些事件维护花名册）。 */
    data class Joined(val seat: Int, val name: String) : ChatEvent

    /** 有人走了。 */
    data class Left(val seat: Int) : ChatEvent

    /** 一条聊天消息。房主广播给所有人（含发送者自己），客户端不做本地回显。 */
    data class Log(val seat: Int, val text: String) : ChatEvent

    /** 服务端拒绝/提示。 */
    data class Error(val reason: String) : ChatEvent
}

/**
 * 聊天室的行协议：`类型|字段|字段`，字段数固定，**自由文本永远是最后一个字段**，
 * 所以文本里带 `|` 不会被切错（换行已由 `:core` 的 `Wire` 禁掉）。
 *
 * ```
 * 客户端 → 服务端    JOIN|<昵称>              SAY|<内容>
 * 服务端 → 客户端    WELCOME|<座位>|<房名>     JOINED|<座位>|<昵称>
 *                   LEFT|<座位>               LOG|<座位>|<内容>
 *                   ERROR|<原因>
 * ```
 *
 * **只认座位，不认昵称**：昵称可能重复、以后可能能改，座位在一条连接的生命周期里稳定，
 * 消息归属靠它。
 *
 * 解析失败一律返回 `null`（坏包不该让谁的进程崩），调用方忽略或用 [error] 回一句。
 */
object ChatProtocol {

    const val MAX_NAME = 12
    const val MAX_TEXT = 300
    const val MAX_ROOM = 24
    private const val MAX_REASON = 80

    // ── 编码 ────────────────────────────────────────────────────────────────

    fun join(name: String): String = "JOIN|" + clean(name, MAX_NAME)

    fun say(text: String): String = "SAY|" + clean(text, MAX_TEXT)

    fun welcome(seat: Int, roomName: String): String = "WELCOME|$seat|" + clean(roomName, MAX_ROOM)

    fun joined(seat: Int, name: String): String = "JOINED|$seat|" + clean(name, MAX_NAME)

    fun left(seat: Int): String = "LEFT|$seat"

    fun log(seat: Int, text: String): String = "LOG|$seat|" + clean(text, MAX_TEXT)

    fun error(reason: String): String = "ERROR|" + clean(reason, MAX_REASON)

    // ── 解码 ────────────────────────────────────────────────────────────────

    fun decodeCommand(line: String): ChatCommand? {
        val (type, tail) = head(line) ?: return null
        return when (type) {
            "JOIN" -> ChatCommand.Join(clean(tail, MAX_NAME))
            "SAY" -> ChatCommand.Say(clean(tail, MAX_TEXT))
            else -> null // 不认识的命令：版本不一致时也别炸
        }
    }

    fun decodeEvent(line: String): ChatEvent? {
        val (type, tail) = head(line) ?: return null
        return when (type) {
            "WELCOME" -> seatAndRest(tail)?.let { (seat, rest) -> ChatEvent.Welcome(seat, clean(rest, MAX_ROOM)) }
            "JOINED" -> seatAndRest(tail)?.let { (seat, rest) -> ChatEvent.Joined(seat, clean(rest, MAX_NAME)) }
            "LEFT" -> tail.toIntOrNull()?.let { ChatEvent.Left(it) }
            "LOG" -> seatAndRest(tail)?.let { (seat, rest) -> ChatEvent.Log(seat, clean(rest, MAX_TEXT)) }
            "ERROR" -> ChatEvent.Error(clean(tail, MAX_REASON))
            else -> null
        }
    }

    // ── 切分 ────────────────────────────────────────────────────────────────

    /** `类型|剩下的一切`。类型为空或没有竖线都当坏包。 */
    private fun head(line: String): Pair<String, String>? {
        val index = line.indexOf('|')
        return if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
    }

    /** `座位|剩下的一切`：只切第一个竖线，后面的原样保留——文本里的 `|` 才不会丢。 */
    private fun seatAndRest(tail: String): Pair<Int, String>? {
        val index = tail.indexOf('|')
        val seat = (if (index < 0) tail else tail.substring(0, index)).toIntOrNull() ?: return null
        return seat to (if (index < 0) "" else tail.substring(index + 1))
    }

    /** 去掉换行（协议层不允许）并按上限截断：一条消息绝不能把对端撑爆。 */
    private fun clean(value: String, max: Int): String =
        value.replace('\r', ' ').replace('\n', ' ').trim().take(max)
}
