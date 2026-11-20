package com.poria.netnsd.room

import com.poria.core.game.GameCatalog
import com.poria.core.game.GameInfo

/**
 * 房主侧的房间规则：座位、座次、玩法开关、准备状态、能不能开局。
 *
 * **它是权威**。客户端只发命令，这里改完状态就把**整份快照**广播给所有人（含发起者），
 * 客户端不做任何本地推演/回显——所以"谁在房间里""谁坐几号位"永远只有这一份。
 *
 * **不碰 socket**：每个成员只给 `send` / `close` 两个回调，于是规则能在 JVM 上单测
 * （`RoomTest` 里用几个假的发送器就把座位/权限/踢人测干净）。
 *
 * 权限也在这一层：`#hostOnly` 判定"发命令的人是不是房主"，不看客户端自称什么。
 * UI 把按钮灰掉只是顺手，真正说了算的是这里。
 *
 * ⚠️ 所有命令都在锁内直接调 `send`：某个客户端写阻塞会拖慢整个房间（局域网回合制的量级
 * 可以接受，和 `Peer.send` 的阻塞写是同一个取舍）。别在 `send` 里做重活。
 */
class Room(roomName: String) {

    sealed interface JoinResult {
        data object Joined : JoinResult

        /** 牌桌坐满了（座位数 = 在场人数上限）。 */
        data object Full : JoinResult

        /** 昵称被别人占了。 */
        data object DuplicateName : JoinResult

        /** 这个 playerId 已经在房间里了（同一条连接发了两次 JOIN）。 */
        data object AlreadyJoined : JoinResult

        data object InvalidName : JoinResult

        data object InvalidId : JoinResult
    }

    private class Member(
        val id: String,
        val name: String,
        val host: Boolean,
        val send: (String) -> Boolean,
        val close: () -> Unit,
        var seat: Int,
        var ready: Boolean = false,
        /** 头像的内容 id；空串 = 没设。**房间只存 id，图片本体在 BlobStore 里。** */
        var avatar: String = "",
    )

    val roomName: String = sanitizeRoom(roomName)

    private val lock = Any()
    private val members = LinkedHashMap<String, Member>()
    private val messages = ArrayDeque<RoomMessage>()
    private var nextSeq = 1

    private var game: GameInfo = GameCatalog.games.first()
    private var tableSize: Int = GameCatalog.defaultTableSize(game)
    private var phase: RoomPhase = RoomPhase.Lobby
    private val options = LinkedHashMap<String, Boolean>().apply {
        putAll(GameCatalog.defaultOptions(game))
    }

    /** 当前房间状态（测试和调试用；线上客户端拿到的是广播出去的那份）。 */
    fun snapshot(): RoomSnapshot = synchronized(lock) { snapshotLocked() }

    val size: Int get() = synchronized(lock) { members.size }

    /** 在场玩家的昵称，按座位排序。 */
    fun playerNames(): List<String> = synchronized(lock) {
        members.values.sortedBy { it.seat }.map { it.name }
    }

    // ── 进出房间 ────────────────────────────────────────────────────────────

    /**
     * 进房。成功后立刻广播一份新快照（所有人，含新人）。
     *
     * @param host 这条连接是不是房主自己（会话层按"是不是 127.0.0.1 回环"填）。
     */
    fun join(
        playerId: String,
        name: String,
        host: Boolean,
        send: (String) -> Boolean,
        close: () -> Unit,
    ): JoinResult {
        val id = playerId.trim().take(RoomProtocol.MAX_ID)
        if (id.isEmpty()) return JoinResult.InvalidId
        val cleanName = sanitizeName(name)
        if (cleanName.isEmpty()) return JoinResult.InvalidName

        synchronized(lock) {
            if (members.containsKey(id)) return JoinResult.AlreadyJoined
            if (members.size >= tableSize) return JoinResult.Full
            if (members.values.any { it.name == cleanName }) return JoinResult.DuplicateName

            val seat = (0 until tableSize).first { index -> members.values.none { it.seat == index } }
            members[id] = Member(id, cleanName, host, send, close, seat)
            broadcastLocked()
            return JoinResult.Joined
        }
    }

    /** 离开房间。不认识的人直接忽略（比如被踢之后连接才断开）。 */
    fun leave(playerId: String) {
        synchronized(lock) {
            if (members.remove(playerId) == null) return
            broadcastLocked()
        }
    }

    // ── 聊天 / 准备 ─────────────────────────────────────────────────────────

    /**
     * 说一句话。空消息静默忽略（不算错误）；其余情况广播新快照。
     *
     * @return 没生效时的原因，`null` 表示已经生效
     */
    fun say(playerId: String, text: String): String? {
        val clean = sanitizeText(text)
        synchronized(lock) {
            if (!members.containsKey(playerId)) return "你不在房间里了"
            if (clean.isEmpty()) return null

            messages.addLast(RoomMessage(nextSeq++, playerId, clean))
            while (messages.size > MAX_HISTORY) messages.removeFirst()
            broadcastLocked()
            return null
        }
    }

    fun setReady(playerId: String, ready: Boolean): String? {
        synchronized(lock) {
            val member = members[playerId] ?: return "你不在房间里了"
            if (phase != RoomPhase.Lobby) return "游戏已经开始了"
            if (member.ready == ready) return null

            member.ready = ready
            broadcastLocked()
            return null
        }
    }

    // ── 头像 ────────────────────────────────────────────────────────────────

    /**
     * 换头像。**没有对应的客户端命令**：图片传完后由 [RoomSession] 调它，
     * 因为"这个 id 的图真的已经收全了"只有会话层（拿着 [BlobStore] 的那层）知道。
     *
     * @param avatarId 内容 id（sha1）或 `builtin:` 素材 id；空串表示清掉头像
     */
    fun setAvatar(playerId: String, avatarId: String): String? {
        val clean = avatarId.trim().take(RoomProtocol.MAX_BLOB_ID)
        if (clean.isNotEmpty() && !BlobStore.isBuiltin(clean) && !BlobStore.isValidId(clean)) {
            return "头像的内容 id 不合法"
        }

        synchronized(lock) {
            val member = members[playerId] ?: return "你不在房间里了"
            if (member.avatar == clean) return null

            member.avatar = clean
            broadcastLocked()
            return null
        }
    }

    // ── 座位 ────────────────────────────────────────────────────────────────

    /** 自己挪到空座位。 */
    fun takeSeat(playerId: String, seat: Int): String? {
        synchronized(lock) {
            val member = members[playerId] ?: return "你不在房间里了"
            if (phase != RoomPhase.Lobby) return "游戏已经开始了，不能换座"
            if (seat !in 0 until tableSize) return "没有 $seat 号座位"
            if (member.seat == seat) return null
            if (members.values.any { it.seat == seat }) return "那个座位有人了"

            member.seat = seat
            broadcastLocked()
            return null
        }
    }

    /** 房主：交换两个座位（一个是空位就是挪过去）。 */
    fun swapSeats(playerId: String, a: Int, b: Int): String? {
        synchronized(lock) {
            hostOnly(playerId)?.let { return it }
            if (phase != RoomPhase.Lobby) return "游戏已经开始了，不能换座"
            if (a == b) return null
            if (a !in 0 until tableSize || b !in 0 until tableSize) return "座位号超出牌桌人数"

            val first = members.values.firstOrNull { it.seat == a }
            val second = members.values.firstOrNull { it.seat == b }
            if (first == null && second == null) return "两个座位都是空的"

            first?.seat = b
            second?.seat = a
            broadcastLocked()
            return null
        }
    }

    /** 房主：改牌桌人数。改小了会把越界的人自动挪到最近的空座位。 */
    fun setTableSize(playerId: String, size: Int): String? {
        synchronized(lock) {
            hostOnly(playerId)?.let { return it }
            if (phase != RoomPhase.Lobby) return "游戏已经开始了"
            if (size !in game.minPlayers..game.maxPlayers) {
                return "「${game.title}」只能 ${game.minPlayers}–${game.maxPlayers} 人"
            }
            if (size < members.size) return "先把人请出去，才能减到 $size 个座位"
            if (size == tableSize) return null

            tableSize = size
            reseatLocked()
            clearReadyLocked()
            broadcastLocked()
            return null
        }
    }

    // ── 游戏 / 玩法 ─────────────────────────────────────────────────────────

    fun selectGame(playerId: String, gameId: String): String? {
        synchronized(lock) {
            hostOnly(playerId)?.let { return it }
            if (phase != RoomPhase.Lobby) return "游戏已经开始了"
            val picked = GameCatalog.byId(gameId) ?: return "没有这个游戏：$gameId"
            if (picked.id == game.id) return null
            if (members.size > picked.maxPlayers) return "「${picked.title}」最多 ${picked.maxPlayers} 人"

            game = picked
            tableSize = GameCatalog.defaultTableSize(picked)
            options.clear()
            options.putAll(GameCatalog.defaultOptions(picked))
            reseatLocked()
            clearReadyLocked()
            broadcastLocked()
            return null
        }
    }

    fun setOption(playerId: String, optionId: String, on: Boolean): String? {
        synchronized(lock) {
            hostOnly(playerId)?.let { return it }
            if (phase != RoomPhase.Lobby) return "游戏已经开始了"
            if (GameCatalog.option(game.id, optionId) == null) return "「${game.title}」没有这个玩法"
            if (options[optionId] == on) return null

            options[optionId] = on
            clearReadyLocked()
            broadcastLocked()
            return null
        }
    }

    // ── 踢人 / 开局 ─────────────────────────────────────────────────────────

    fun kick(playerId: String, targetId: String): String? {
        var close: (() -> Unit)? = null
        synchronized(lock) {
            hostOnly(playerId)?.let { return it }
            if (targetId == playerId) return "不能把自己请出去"
            val target = members.remove(targetId) ?: return "这个人已经不在房间里了"

            target.send(RoomProtocol.bye("房主把你请出了房间"))
            close = target.close
            broadcastLocked()
        }
        // 关连接放在锁外：close() 会唤醒那条连接的读线程，让它回来调 leave()
        close?.invoke()
        return null
    }

    /**
     * 房主：开始游戏。校验顺序是"人数够 → 所有人都准备好"，都过了以后才轮到规则本身。
     *
     * 现在规则还没写（`GameInfo.available == false`），所以最后一步返回的是一句提示——
     * **这里就是将来接对局的地方**：把 return 换成 `phase = RoomPhase.Playing` + 广播即可。
     */
    fun start(playerId: String): String? {
        synchronized(lock) {
            hostOnly(playerId)?.let { return it }
            if (phase != RoomPhase.Lobby) return "已经开始了"

            val hint = startHintLocked()
            if (hint.isNotEmpty()) return hint

            return "「${game.title}」的对局还在施工，先把人凑齐、座次排好"
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun hostOnly(playerId: String): String? {
        val host = members.values.firstOrNull { it.host } ?: return "房主不在，改不了设置"
        return if (host.id == playerId) null else "只有房主能改设置"
    }

    /**
     * "现在还开不了局"的原因，空串表示可以开始。
     *
     * 它进快照，所以**只有这一份规则**：客户端拿它显示在按钮上，不自己再判一遍；
     * 房主真按了「开始」也是走 [start]，两处用的是同一个函数。
     */
    private fun startHintLocked(): String {
        if (phase != RoomPhase.Lobby) return "已经开始了"
        val missing = game.minPlayers - members.size
        if (missing > 0) return "还差 $missing 人"
        val waiting = members.values.filter { !it.host && !it.ready }
        if (waiting.isNotEmpty()) return "等 ${waiting.joinToString("、") { it.name }} 准备"
        return ""
    }

    private fun clearReadyLocked() {
        members.values.forEach { it.ready = false }
    }

    /** 把坐在已经不存在的座位上的人挪到最近的空位（改小牌桌、换游戏之后用）。 */
    private fun reseatLocked() {
        val taken = BooleanArray(tableSize)
        val homeless = ArrayList<Member>()
        members.values.sortedBy { it.seat }.forEach { member ->
            val seat = member.seat
            if (seat in 0 until tableSize && !taken[seat]) taken[seat] = true else homeless.add(member)
        }
        var cursor = 0
        homeless.forEach { member ->
            while (cursor < tableSize && taken[cursor]) cursor++
            if (cursor < tableSize) {
                taken[cursor] = true
                member.seat = cursor
            }
        }
    }

    private fun broadcastLocked() {
        val line = RoomProtocol.snapshot(snapshotLocked())
        members.values.forEach { it.send(line) }
    }

    private fun snapshotLocked(): RoomSnapshot = RoomSnapshot(
        roomName = roomName,
        hostId = members.values.firstOrNull { it.host }?.id.orEmpty(),
        phase = phase,
        gameId = game.id,
        tableSize = tableSize,
        startHint = startHintLocked(),
        options = LinkedHashMap(options),
        players = members.values.sortedBy { it.seat }.map { member ->
            RoomPlayer(member.id, member.seat, member.name, member.host, member.ready, member.avatar)
        },
        messages = messages.toList(),
    )

    private fun sanitizeName(value: String): String = sanitize(value, RoomProtocol.MAX_NAME)

    private fun sanitizeText(value: String): String = sanitize(value, RoomProtocol.MAX_TEXT)

    private fun sanitizeRoom(value: String): String = sanitize(value, RoomProtocol.MAX_ROOM)

    private fun sanitize(value: String, max: Int): String =
        value.replace('\r', ' ').replace('\n', ' ').trim().take(max)

    companion object {
        /** 聊天记录最多留这么多条（快照太大时还会再丢最老的）。 */
        const val MAX_HISTORY = 40
    }
}
