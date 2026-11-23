package com.poria.netnsd.lobby

import com.poria.netnsd.link.LinkOut
import com.poria.netnsd.link.PlayerRef
import com.poria.netnsd.link.TcpHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * `lobby` 频道（`ns = "lobby"`）。**一个频道一个类**，两半都在这里：
 *
 * - **服务器半边**（只有服务器有 [lobby]）：`onCommand` 收命令 → 改 [Lobby] → 推快照；
 * - **客户端半边**（两边都有）：`onEvent` 收快照 → 变成 [state]。
 *
 * 为什么合成一个类：服务器那条链路上既会收到客人发来的命令（服务器侧），也会收到自己
 * 广播出去的快照（客户端侧，经回环），而 `TcpLink` 的注册表一个 `ns` 只放一个 Handler。
 *
 * 客户端不攒状态：没有第二条名册，收到什么快照就是什么；服务器的"拒绝"进 `note`。
 *
 * 本机身份（[LobbyState.myId]）由链路分配后从 [onLinked] 送进来，不是自己编的。
 * **名字和头像不在这里**——那是 `profile` 频道的事。
 */
class LobbyChannel internal constructor(
    /** 只有服务器传房间名；纯客户端传 null（没有服务器半边）。 */
    roomName: String?,
    host: Boolean,
) : TcpHandler {

    override val ns: String = LobbyProtocol.NS

    /** 服务器半边：只有服务器有。 */
    private val lobby: Lobby? = roomName?.let { Lobby(it) }

    /** 服务器半边和客户端半边要串起来：命令处理 + 广播必须按顺序。 */
    private val lock = Any()

    @Volatile
    private var out: LinkOut? = null

    private val _state = MutableStateFlow(LobbyState(host = host))

    /** 客户端半边：最近一份快照 + 连接状态。 */
    val state: StateFlow<LobbyState> = _state.asStateFlow()

    /** 服务器侧房间名（已经清洗过，NSD 广播用）；纯客户端是 null。 */
    val roomName: String? get() = lobby?.roomName

    override fun attach(out: LinkOut) {
        this.out = out
    }

    /** 服务器分了身份：记下来，快照里"哪一行是我"就靠它。 */
    override fun onLinked(localId: String) {
        _state.update { it.copy(myId = localId) }
    }

    // ── 客户端半边（两边都有）──────────────────────────────────────────────

    override fun onEvent(payload: JsonElement?, from: String?) {
        when (val event = LobbyProtocol.decodeEvent(payload) ?: return) {
            is LobbyProtocol.Event.Snapshot -> {
                // 版本不认识的快照整份丢掉：宁可不动，也不渲染半份状态
                if (event.lobby.version != LobbyProtocol.VERSION) return
                _state.update {
                    it.copy(phase = SessionPhase.ONLINE, snapshot = event.lobby, note = null)
                }
            }

            is LobbyProtocol.Event.Rejected -> _state.update { it.copy(note = event.reason) }
        }
    }

    override fun onLinkClosed(reason: String?) {
        _state.update {
            it.copy(phase = SessionPhase.CLOSED, note = it.note ?: reason ?: "连接断了")
        }
    }

    /** 会话层建连失败时用：落到 CLOSED 并带上原因。 */
    fun fail(reason: String) {
        _state.update { it.copy(phase = SessionPhase.CLOSED, note = it.note ?: reason) }
    }

    /** 只想在状态栏提示一句（比如 NSD 广播失败），不影响连接状态。 */
    fun attention(reason: String) {
        _state.update { it.copy(note = it.note ?: reason) }
    }

    // ── 服务器半边（只有服务器有）──────────────────────────────────────────

    override fun onPeerLeft(playerId: String) {
        synchronized(lock) {
            if (lobby?.leave(playerId) == true) publish()
        }
    }

    override fun onCommand(player: PlayerRef, payload: JsonElement?) {
        if (lobby == null) return // 纯客户端收到命令说明对端搞错了，忽略
        val command = LobbyProtocol.decodeCommand(payload) ?: return

        synchronized(lock) {
            if (command is LobbyProtocol.Command.Join) {
                onJoin(player)
            } else {
                onAction(player.id, command)
            }
        }
    }

    /** 现在大厅里的快照（服务器才有；测试和将来接游戏用）。 */
    fun snapshot(): LobbySnapshot? = lobby?.let { synchronized(lock) { it.snapshot() } }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun onJoin(player: PlayerRef) {
        val current = lobby ?: return
        when (current.join(player.id, player.isHost)) {
            Lobby.JoinResult.Joined -> publish()
            Lobby.JoinResult.Full -> reject(player.id, "牌桌坐满了")
            Lobby.JoinResult.AlreadyJoined -> reject(player.id, "你已经在这间房里了")
            Lobby.JoinResult.InvalidId -> reject(player.id, "客户端身份不合法")
        }
    }

    private fun onAction(playerId: String, command: LobbyProtocol.Command) {
        val current = lobby ?: return
        val before = current.version
        val rejection = when (command) {
            is LobbyProtocol.Command.Say -> current.say(playerId, command.text)
            is LobbyProtocol.Command.Ready -> current.setReady(playerId, command.ready)
            is LobbyProtocol.Command.TakeSeat -> current.takeSeat(playerId, command.seat)
            is LobbyProtocol.Command.SwapSeats -> current.swapSeats(playerId, command.a, command.b)
            is LobbyProtocol.Command.SetTableSize -> current.setTableSize(playerId, command.size)
            is LobbyProtocol.Command.SelectGame -> current.selectGame(playerId, command.gameId)
            is LobbyProtocol.Command.SetOption -> current.setOption(playerId, command.optionId, command.on)
            is LobbyProtocol.Command.Start -> current.start(playerId)
            is LobbyProtocol.Command.Kick -> current.kick(playerId, command.playerId)
            LobbyProtocol.Command.Join -> null // 上面已经处理过了
        }

        when {
            rejection != null -> reject(playerId, rejection)
            current.version != before -> publish()
        }

        if (command is LobbyProtocol.Command.Kick && rejection == null) {
            // 快照已经广播过（房间里没他了），再把那条连接断掉
            out?.disconnect(command.playerId, "房主把你请出了房间")
        }
    }

    private fun publish() {
        val current = lobby ?: return
        val snapshot = current.snapshot()
        val payload = LobbyProtocol.encodeSnapshot(snapshot)
        // 只发给**房间成员**：链路里报到过、但还没进房（或刚被拒/刚被踢）的人不该看到房间状态
        val members = snapshot.players.mapTo(HashSet(snapshot.players.size)) { it.id }
        out?.broadcastEach { id -> if (id in members) payload else null }
    }

    private fun reject(playerId: String, reason: String) {
        out?.sendTo(playerId, LobbyProtocol.rejected(reason))
    }
}
