package com.poria.netnsd.room

/**
 * 客户端 → 服务端的命令。客户端只能"请求"，能不能改由 [Room] 说了算。
 *
 * 客户端**不做本地回显/推演**：发完命令什么都不改，等服务端把新的
 * [RoomSnapshot] 推回来才动界面。
 */
sealed interface RoomCommand {
    /** 进房报到。[playerId] 由客户端自己生成，服务端认可后就是这间房里的身份。 */
    data class Join(val playerId: String, val name: String) : RoomCommand

    data class Say(val text: String) : RoomCommand

    /** 我准备好了 / 取消准备。 */
    data class Ready(val ready: Boolean) : RoomCommand

    /** 自己挪到一个**空**座位（有人坐的座位只能房主调）。 */
    data class TakeSeat(val seat: Int) : RoomCommand

    /** 房主：交换两个座位（其中一个是空位就是"把人挪过去"）。 */
    data class SwapSeats(val a: Int, val b: Int) : RoomCommand

    /** 房主：改牌桌人数。 */
    data class SetTableSize(val size: Int) : RoomCommand

    /** 房主：换游戏。 */
    data class SelectGame(val gameId: String) : RoomCommand

    /** 房主：开关一个自定义玩法。 */
    data class SetOption(val optionId: String, val on: Boolean) : RoomCommand

    /** 房主：请人离开。 */
    data class Kick(val playerId: String) : RoomCommand

    /** 房主：开始游戏（对局规则没写之前会被服务端挡下）。 */
    data object Start : RoomCommand
}

/** 服务端 → 客户端的事件。只有三类：权威快照、单条错误、请你离开。 */
sealed interface RoomEvent {
    /** **唯一的数据通道**：房间每次变化，服务端把整份快照推给所有人（含发起者）。 */
    data class Snapshot(val snapshot: RoomSnapshot) : RoomEvent

    /** 刚才那条命令没生效，[reason] 直接给人看；房间状态没动。 */
    data class Error(val reason: String) : RoomEvent

    /** 服务端要关掉这条连接（满员、被踢、房间解散），[reason] 直接给人看。 */
    data class Bye(val reason: String) : RoomEvent
}

/**
 * 房间的行协议：`类型|字段|字段`，一条消息一行（`:core` 的 `Wire` 保证没有换行）。
 *
 * 和旧聊天协议最大的区别：**服务端只推快照**。以前是 `JOINED` / `LEFT` / `LOG`
 * 一串增量事件让客户端自己攒花名册——攒错一次就永远两边不一致；现在是整份快照覆盖，
 * 客户端没有"我的花名册"这个概念，只有"最近一次收到的快照"。
 *
 * ```
 * 客户端 → 服务端
 *   JOIN|<playerId>|<昵称>     SAY|<内容>              READY|<0|1>
 *   SEAT|<座位>                SWAP|<a>|<b>            SIZE|<人数>
 *   GAME|<游戏id>              OPT|<玩法id>|<0|1>      KICK|<playerId>
 *   START
 *
 * 服务端 → 客户端
 *   SNAP|<快照>                ERROR|<原因>            BYE|<原因>
 * ```
 *
 * 快照是一个 `键=值` 组成的长行，值里出现的 `% | ; , =` 和换行都会被转义
 * （[esc] / [unesc]）。所以昵称、房名、聊天内容里带任何分隔符都不会切错。
 * 单行有上限（`:core` 的 `Wire` 是 8KB），快照超预算时从**最老的聊天**开始丢。
 */
object RoomProtocol {

    /** 快照版本。字段增删要跟着改，客户端认不出就整份丢掉（宁可不动，也不渲染半份状态）。 */
    const val VERSION = 1

    const val MAX_NAME = 12
    const val MAX_TEXT = 300
    const val MAX_ROOM = 24
    const val MAX_ID = 16
    private const val MAX_REASON = 80

    /** 快照单行的目标字节数：比 `Wire.MAX_LINE_BYTES`（8KB）留出余量。 */
    const val MAX_SNAPSHOT_BYTES = 6_000

    // ── 编码：客户端 → 服务端 ────────────────────────────────────────────────

    fun join(playerId: String, name: String): String =
        "JOIN|" + esc(playerId.take(MAX_ID)) + "|" + esc(clean(name, MAX_NAME))

    fun say(text: String): String = "SAY|" + esc(clean(text, MAX_TEXT))

    fun ready(on: Boolean): String = "READY|" + flag(on)

    fun takeSeat(seat: Int): String = "SEAT|$seat"

    fun swapSeats(a: Int, b: Int): String = "SWAP|$a|$b"

    fun setTableSize(size: Int): String = "SIZE|$size"

    fun selectGame(gameId: String): String = "GAME|" + esc(gameId)

    fun setOption(optionId: String, on: Boolean): String = "OPT|" + esc(optionId) + "|" + flag(on)

    fun kick(playerId: String): String = "KICK|" + esc(playerId)

    fun start(): String = "START"

    // ── 编码：服务端 → 客户端 ────────────────────────────────────────────────

    /**
     * 整份快照。超过 [MAX_SNAPSHOT_BYTES] 时从**最老的聊天**开始丢：
     * 座位/设置/人永远是完整的，丢的只会是聊天记录。
     */
    fun snapshot(snapshot: RoomSnapshot): String {
        var messages = snapshot.messages
        while (true) {
            val line = buildSnapshot(snapshot, messages)
            if (messages.size <= 1 || line.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) return line
            messages = messages.subList(1, messages.size)
        }
    }

    fun error(reason: String): String = "ERROR|" + esc(clean(reason, MAX_REASON))

    fun bye(reason: String): String = "BYE|" + esc(clean(reason, MAX_REASON))

    // ── 解码 ────────────────────────────────────────────────────────────────

    /** 解析失败一律返回 `null`：坏包不该让谁的进程崩，也不该改动任何状态。 */
    fun decodeCommand(line: String): RoomCommand? {
        val (type, tail) = head(line)
        return when (type) {
            "JOIN" -> {
                val index = tail.indexOf('|')
                if (index <= 0) {
                    null
                } else {
                    val id = unesc(tail.substring(0, index)).trim().take(MAX_ID)
                    val name = clean(unesc(tail.substring(index + 1)), MAX_NAME)
                    if (id.isEmpty()) null else RoomCommand.Join(id, name)
                }
            }

            "SAY" -> RoomCommand.Say(clean(unesc(tail), MAX_TEXT))

            "READY" -> when (tail) {
                "1" -> RoomCommand.Ready(true)
                "0" -> RoomCommand.Ready(false)
                else -> null
            }

            "SEAT" -> tail.toIntOrNull()?.let { RoomCommand.TakeSeat(it) }

            "SWAP" -> pair(tail)?.let { (a, b) -> RoomCommand.SwapSeats(a, b) }

            "SIZE" -> tail.toIntOrNull()?.let { RoomCommand.SetTableSize(it) }

            "GAME" -> unesc(tail).trim().let { if (it.isEmpty()) null else RoomCommand.SelectGame(it) }

            "OPT" -> {
                val index = tail.indexOf('|')
                if (index <= 0) {
                    null
                } else {
                    val id = unesc(tail.substring(0, index)).trim()
                    val on = tail.substring(index + 1)
                    when {
                        id.isEmpty() || (on != "0" && on != "1") -> null
                        else -> RoomCommand.SetOption(id, on == "1")
                    }
                }
            }

            "KICK" -> unesc(tail).trim().let { if (it.isEmpty()) null else RoomCommand.Kick(it) }

            "START" -> RoomCommand.Start

            else -> null // 不认识的命令：版本不一致时也别炸
        }
    }

    fun decodeEvent(line: String): RoomEvent? {
        val (type, tail) = head(line)
        return when (type) {
            "SNAP" -> decodeSnapshot(tail)?.let { RoomEvent.Snapshot(it) }
            "ERROR" -> RoomEvent.Error(clean(unesc(tail), MAX_REASON))
            "BYE" -> RoomEvent.Bye(clean(unesc(tail), MAX_REASON))
            else -> null
        }
    }

    // ── 快照的拼装与拆解 ────────────────────────────────────────────────────

    private fun buildSnapshot(snapshot: RoomSnapshot, messages: List<RoomMessage>): String = buildString(512) {
        append("SNAP")
        append("|v=").append(VERSION)
        append("|room=").append(esc(snapshot.roomName))
        append("|host=").append(esc(snapshot.hostId))
        append("|phase=").append(if (snapshot.phase == RoomPhase.LOBBY) "lobby" else "playing")
        append("|game=").append(esc(snapshot.gameId))
        append("|size=").append(snapshot.tableSize)
        append("|hint=").append(esc(snapshot.startHint))

        append("|opt=")
        snapshot.options.entries.forEachIndexed { index, (id, on) ->
            if (index > 0) append(';')
            append(esc(id)).append('=').append(flag(on))
        }

        append("|p=")
        snapshot.players.forEachIndexed { index, player ->
            if (index > 0) append(';')
            append(esc(player.id)).append(',').append(player.seat).append(',')
            append(esc(player.name)).append(',')
            append(if (player.host) 'h' else '-').append(if (player.ready) 'r' else '-')
        }

        append("|msg=")
        messages.forEachIndexed { index, message ->
            if (index > 0) append(';')
            append(message.seq).append(',').append(esc(message.playerId)).append(',').append(esc(message.text))
        }
    }

    private fun decodeSnapshot(tail: String): RoomSnapshot? {
        if (tail.isEmpty()) return null

        val fields = HashMap<String, String>()
        tail.split('|').forEach { part ->
            val index = part.indexOf('=')
            if (index <= 0) return null // 键为空或没有等号：不是我们写的快照
            fields[part.substring(0, index)] = part.substring(index + 1)
        }

        if (fields["v"]?.toIntOrNull() != VERSION) return null
        val phase = when (fields["phase"]) {
            "lobby" -> RoomPhase.LOBBY
            "playing" -> RoomPhase.PLAYING
            else -> return null
        }
        val size = fields["size"]?.toIntOrNull() ?: return null
        if (size <= 0) return null

        val players = (fields["p"] ?: return null).let { raw ->
            if (raw.isEmpty()) emptyList() else raw.split(';').map { decodePlayer(it) ?: return null }
        }
        val options = (fields["opt"] ?: return null).let { raw ->
            if (raw.isEmpty()) {
                emptyMap()
            } else {
                val map = LinkedHashMap<String, Boolean>()
                raw.split(';').forEach { item ->
                    val index = item.indexOf('=')
                    if (index <= 0) return null
                    map[unesc(item.substring(0, index))] = item.substring(index + 1) == "1"
                }
                map
            }
        }
        val messages = (fields["msg"] ?: return null).let { raw ->
            if (raw.isEmpty()) emptyList() else raw.split(';').map { decodeMessage(it) ?: return null }
        }

        return RoomSnapshot(
            version = VERSION,
            roomName = unesc(fields["room"] ?: return null),
            hostId = unesc(fields["host"] ?: return null),
            phase = phase,
            gameId = unesc(fields["game"] ?: return null),
            tableSize = size,
            startHint = unesc(fields["hint"] ?: ""),
            options = options,
            players = players,
            messages = messages,
        )
    }

    private fun decodePlayer(raw: String): RoomPlayer? {
        val parts = raw.split(',')
        if (parts.size != 4) return null
        val seat = parts[1].toIntOrNull() ?: return null
        val flags = parts[3]
        return RoomPlayer(
            id = unesc(parts[0]),
            seat = seat,
            name = unesc(parts[2]),
            host = flags.contains('h'),
            ready = flags.contains('r'),
        )
    }

    private fun decodeMessage(raw: String): RoomMessage? {
        val parts = raw.split(',')
        if (parts.size != 3) return null
        val seq = parts[0].toIntOrNull() ?: return null
        return RoomMessage(seq = seq, playerId = unesc(parts[1]), text = unesc(parts[2]))
    }

    // ── 切分 / 清洗 / 转义 ──────────────────────────────────────────────────

    /** `类型|剩下的一切`。没有竖线（比如 `START`）时 tail 是空串。 */
    private fun head(line: String): Pair<String, String> {
        val index = line.indexOf('|')
        return if (index < 0) line to "" else line.substring(0, index) to line.substring(index + 1)
    }

    /** `a|b` 两个整数，多一个少一个都当坏包。 */
    private fun pair(tail: String): Pair<Int, Int>? {
        val index = tail.indexOf('|')
        if (index <= 0) return null
        val a = tail.substring(0, index).toIntOrNull() ?: return null
        val b = tail.substring(index + 1).toIntOrNull() ?: return null
        return a to b
    }

    private fun flag(on: Boolean): String = if (on) "1" else "0"

    /** 去掉换行（行协议不允许）并按上限截断。 */
    private fun clean(value: String, max: Int): String =
        value.replace('\r', ' ').replace('\n', ' ').trim().take(max)

    /**
     * 转义：`% | ; , =` 和换行都换成 `%XX`，于是任何用户输入都能安全塞进
     * "逗号分字段、分号分记录、竖线分大字段"的结构里。其它字符原样保留（中文不需要转义）。
     */
    internal fun esc(value: String): String {
        if (value.none { it in ESCAPED }) return value
        return buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '%' -> append("%25")
                    '|' -> append("%7C")
                    ';' -> append("%3B")
                    ',' -> append("%2C")
                    '=' -> append("%3D")
                    '\r' -> append("%0D")
                    '\n' -> append("%0A")
                    else -> append(char)
                }
            }
        }
    }

    internal fun unesc(value: String): String {
        if (value.indexOf('%') < 0) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    index += 3
                    continue
                }
            }
            out.append(char)
            index++
        }
        return out.toString()
    }

    private val ESCAPED = charArrayOf('%', '|', ';', ',', '=', '\r', '\n')
}
