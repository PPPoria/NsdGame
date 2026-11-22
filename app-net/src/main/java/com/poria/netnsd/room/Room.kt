package com.poria.netnsd.room

import com.poria.core.game.GameCatalog
import com.poria.core.game.GameInfo
import com.poria.netnsd.blob.BlobStore

/**
 * 房主侧的房间规则：座位、座次、玩法开关、准备状态、能不能开局。
 *
 * **它是权威，但它不说话**：所有方法只改自己的状态，返回"没生效的原因"（`null` = 生效了），
 * **不碰 socket、不广播**。推快照是 [LobbyChannel] 的事——它拿着 `LinkOut`，每次改完看一眼
 * [version] 变没变，变了就 `broadcastEach(snapshot())`。
 *
 * 这样房间规则能在 JVM 上单测（`RoomTest` 只对着 `snapshot()` 断言），
 * 而"谁该收到"这种联网问题完全不在这一层。
 *
 * **客户端不存状态**：客户端那边是同一个 [LobbyChannel] 的客户端半边拿着最近一份快照，
 * 没有第二份花名册。
 */
class Room(roomName: String) {

    sealed interface JoinResult {
        data object Joined : JoinResult

        /** 牌桌坐满了（座位数 = 在场人数上限）。 */
        data object Full : JoinResult

        /** 昵称被别人占了。 */
        data object DuplicateName : JoinResult

        /** 这个 playerId 已经在房间里了（同一条连接发了两次 Join）。 */
        data object AlreadyJoined : JoinResult

        data object InvalidName : JoinResult

        data object InvalidId : JoinResult
    }

    private class Member(
        val id: String,
        val name: String,
        val host: Boolean,
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

    /**
     * 状态版本号：每次真的改了东西就 +1。
     *
     * Handler 用"进去之前 / 出来之后"的差来判断要不要广播快照，
     * 于是 Room 的方法不需要区分"成功"和"成功但没变化"（空消息、同一个座位之类）。
     */
    @Volatile
    var version: Int = 0
        private set

    /** 当前房间状态（客户端拿到的就是它序列化后的样子）。 */
    fun snapshot(): RoomSnapshot = synchronized(lock) { snapshotLocked() }

    val size: Int get() = synchronized(lock) { members.size }

    /** 在场玩家的昵称，按座位排序。 */
    fun playerNames(): List<String> = synchronized(lock) {
        members.values.sortedBy { it.seat }.map { it.name }
    }

    // ── 进出房间 ────────────────────────────────────────────────────────────

    /**
     * 进房。座位取最小的空位；失败时**什么都没改**。
     *
     * @param host 是不是房主自己——链路认出来的（回环连上来那条），不是客户端自称的
     */
    fun join(playerId: String, name: String, host: Boolean): JoinResult {
        val id = playerId.trim().take(LobbyProtocol.MAX_ID)
        if (id.isEmpty()) return JoinResult.InvalidId
        val cleanName = sanitizeName(name)
        if (cleanName.isEmpty()) return JoinResult.InvalidName

        synchronized(lock) {
            if (members.containsKey(id)) return JoinResult.AlreadyJoined
            if (members.size >= tableSize) return JoinResult.Full
            if (members.values.any { it.name == cleanName }) return JoinResult.DuplicateName

            val seat = (0 until tableSize).first { index -> members.values.none { it.seat == index } }
            members[id] = Member(id, cleanName, host, seat)
            bumpLocked()
            return JoinResult.Joined
        }
    }

    /**
     * 离开房间。不认识的人直接忽略（被踢之后连接才断开就是这种情况）。
     *
     * @return 房间里真的少了一个人
     */
    fun leave(playerId: String): Boolean = synchronized(lock) {
        if (members.remove(playerId) == null) return false
        bumpLocked()
        true
    }

    // ── 聊天 / 准备 ─────────────────────────────────────────────────────────

    /**
     * 说一句话。空消息静默忽略（不算错误、也不改版本）。
     *
     * @return 没生效时的原因，`null` = 已经生效（或者本来就没什么可做的）
     */
    fun say(playerId: String, text: String): String? {
        val clean = sanitizeText(text)
        synchronized(lock) {
            if (!members.containsKey(playerId)) return "你不在房间里了"
            if (clean.isEmpty()) return null

            messages.addLast(RoomMessage(nextSeq++, playerId, clean))
            while (messages.size > MAX_HISTORY) messages.removeFirst()
            bumpLocked()
            return null
        }
    }

    fun setReady(playerId: String, ready: Boolean): String? {
        synchronized(lock) {
            val member = members[playerId] ?: return "你不在房间里了"
            if (phase != RoomPhase.Lobby) return "游戏已经开始了"
            if (member.ready == ready) return null

            member.ready = ready
            bumpLocked()
            return null
        }
    }

    // ── 头像 ────────────────────────────────────────────────────────────────

    /**
     * 换头像。图片本体不在房间这里：客户端先把图装进自己的 `BlobStore`，
     * 再把内容 id 报上来；别人要看就按 id 去拥有者那儿拉。
     *
     * @param avatarId 内容 id（sha1）或 `builtin:` 素材 id；空串表示清掉头像
     */
    fun setAvatar(playerId: String, avatarId: String): String? {
        val clean = avatarId.trim().take(LobbyProtocol.MAX_AVATAR)
        if (clean.isNotEmpty() && !BlobStore.isBuiltin(clean) && !BlobStore.isValidId(clean)) {
            return "头像的内容 id 不合法"
        }

        synchronized(lock) {
            val member = members[playerId] ?: return "你不在房间里了"
            if (member.avatar == clean) return null

            member.avatar = clean
            bumpLocked()
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
            bumpLocked()
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
            bumpLocked()
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
            bumpLocked()
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
            bumpLocked()
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
            bumpLocked()
            return null
        }
    }

    // ── 踢人 / 开局 ─────────────────────────────────────────────────────────

    /** 房主：把人移出房间。断开那条连接是 Handler 的事（它会发一条 BYE 再关 socket）。 */
    fun kick(playerId: String, targetId: String): String? {
        synchronized(lock) {
            hostOnly(playerId)?.let { return it }
            if (targetId == playerId) return "不能把自己请出去"
            if (members.remove(targetId) == null) return "这个人已经不在房间里了"

            bumpLocked()
            return null
        }
    }

    /**
     * 房主：开始游戏。校验顺序是"人数够 → 所有人都准备好"，都过了以后才轮到规则本身。
     *
     * 现在规则还没写（`GameInfo.available == false`），所以最后一步返回的是一句提示——
     * **这里就是将来接对局的地方**：把 return 换成 `phase = RoomPhase.Playing` + `bumpLocked()` 即可。
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

    private fun bumpLocked() {
        version++
    }

    private fun hostOnly(playerId: String): String? {
        val host = members.values.firstOrNull { it.host } ?: return "房主不在，改不了设置"
        return if (host.id == playerId) null else "只有房主能改设置"
    }

    /**
     * "现在还开不了局"的原因，空串表示可以开始。
     *
     * 它进快照，所以**只有这一份规则**：客户端拿它显示在按钮上，不自己再判一遍。
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

    private fun sanitizeName(value: String): String = sanitize(value, LobbyProtocol.MAX_NAME)

    private fun sanitizeText(value: String): String = sanitize(value, LobbyProtocol.MAX_TEXT)

    private fun sanitizeRoom(value: String): String = sanitize(value, LobbyProtocol.MAX_ROOM)

    private fun sanitize(value: String, max: Int): String =
        value.replace('\r', ' ').replace('\n', ' ').trim().take(max)

    companion object {
        /** 聊天记录最多留这么多条（快照太大时 [LobbyProtocol.encodeSnapshot] 还会再丢最老的）。 */
        const val MAX_HISTORY = 40
    }
}
