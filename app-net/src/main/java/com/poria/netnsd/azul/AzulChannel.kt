package com.poria.netnsd.azul

import com.poria.game.azul.AzulEngine
import com.poria.game.azul.AzulPhase
import com.poria.game.azul.AzulSnapshot
import com.poria.netnsd.link.LinkOut
import com.poria.netnsd.link.PlayerRef
import com.poria.netnsd.link.TcpHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * 对局页要渲染的东西：服务器推来的局面 + 本机身份 + 一句提示。
 *
 * 和大厅的 [com.poria.netnsd.lobby.LobbyState] 是同一个形状，但它没有"连接状态"——
 * 链路早就连着了（连接属于 `Session`），这里只有"对局有没有开局 / 现在该谁动"。
 */
data class AzulGameState(
    /** 服务器分给本机的身份。 */
    val myId: String = "",
    /** 整份局面；`null` = 还没收到（正在等服务器推）。 */
    val snapshot: AzulSnapshot? = null,
    /** 给用户看的一句提示：被拒绝的原因、链接断了。 */
    val note: String? = null,
) {

    val online: Boolean get() = snapshot != null

    val phase: AzulPhase get() = snapshot?.phase ?: AzulPhase.Preparing

    /** 该谁动了；局面还没到就是空串。 */
    val actPlayer: String get() = snapshot?.actPlayer.orEmpty()

    /** 是不是该我动。 */
    val myTurn: Boolean get() = myId.isNotEmpty() && actPlayer == myId

    /** 终局结算阶段。 */
    val over: Boolean get() = phase == AzulPhase.EndSpecialScore
}

/**
 * `azul` 频道（`ns = "azul"`）。和 `lobby` 一样是**一个类两半**：
 *
 * - **服务器半边**（只有房主那台机器上的实例有 [engine]）：收命令 → 让引擎走一步 → 推整份局面；
 * - **客户端半边**（两边都有）：收局面 → 变成 [state]。
 *
 * 为什么合成一个类：房主那条 `TcpLink` 既会收到客人发来的命令（服务器侧），
 * 也会收到自己广播出去的局面（客户端侧，经回环），而一个 `ns` 只能注册一个 Handler。
 *
 * ## 和 `lobby` 的三处不同
 *
 * 1. **引擎不在这里存规则**：规则全在 `:game-core` 的 [AzulEngine]，这个类只负责
 *    "谁的回合能发什么、该广播给谁"。引擎的 `check(...)` 抛异常 = 这步走不了，
 *    这里 `runCatching` 成一句拒绝原因回给那个人。
 * 2. **回合校验在这里做**：引擎的 `selectTilesFromPlateToRow` 不认人（它只关心棋盘），
 *    所以"是不是该你动"由频道判——越权的话引擎一样会走，那就成作弊了。
 * 3. **没有隐藏信息**：花砖的所有人是公开的，整份 `broadcast` 就行。
 */
class AzulChannel internal constructor(
    /** 服务器侧（房主）传座位顺序（= 开局顺序）；纯客户端传 null。 */
    seatOrder: List<String>? = null,
    /** 一轮结算的画面停留多久再自动开下一轮（测试里调小）。 */
    private val roundSettleMs: Long = ROUND_SETTLE_MS,
) : TcpHandler {

    override val ns: String = AzulProtocol.NS

    /** 服务器侧：引擎 + 开局顺序。纯客户端两者都没有。 */
    private val seatOrder: List<String>? = seatOrder

    private val engine: AzulEngine? = if (seatOrder == null) null else AzulEngine()

    /** 命令处理 + 广播要串行，否则两份局面可能乱序到达。 */
    private val lock = Any()

    /** 只给"结算后延时开下一轮"用；链路一关就整个取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var settleJob: Job? = null

    @Volatile
    private var out: LinkOut? = null

    private val _state = MutableStateFlow(AzulGameState())

    /** 客户端半边：最近一份局面 + 提示。 */
    val state: StateFlow<AzulGameState> = _state.asStateFlow()

    /** 服务器侧才有的引擎（测试和将来"回放"用）；纯客户端是 null。 */
    val serverEngine: AzulEngine? get() = engine

    override fun attach(out: LinkOut) {
        this.out = out
        val order = seatOrder
        if (order == null) {
            // 客户端：先把当前局面要一份（比我早进页面的人已经拿到了）
            out.sendCommand(AzulProtocol.encode(AzulProtocol.Command.Sync))
        } else {
            startServerGame(order)
        }
    }

    override fun onLinked(localId: String) {
        _state.update { it.copy(myId = localId) }
    }

    // ── 服务器半边 ──────────────────────────────────────────────────────────

    override fun onCommand(player: PlayerRef, payload: JsonElement?) {
        val engine = engine ?: return // 纯客户端收到命令说明对端搞错了
        val command = AzulProtocol.decodeCommand(payload) ?: return

        synchronized(lock) {
            if (command is AzulProtocol.Command.Sync) {
                // 要一份当前局面：直接广播一份（`from = null`），比 `sendTo` 干净——
                // 客户端就能用一条规则「局面只认不带落款的帧」把伪造的挡掉
                publish()
                return
            }

            val rejection = apply(player.id, command)
            if (rejection != null) {
                out?.sendTo(player.id, AzulProtocol.rejected(rejection))
                return
            }

            // 拿完最后一块砖：这一轮就结束了，**不用谁再点一下**
            val settled = engine.nowCanFinishRound
            if (settled) runCatching { engine.finishRound() }
            publish()
            // 结算画面让人看一眼，过一会儿自动开下一轮（或者进终局结算）
            if (settled) scheduleNextRound()
        }
    }

    // ── 客户端半边 ──────────────────────────────────────────────────────────

    override fun onEvent(payload: JsonElement?, from: String?) {
        when (val event = AzulProtocol.decodeEvent(payload) ?: return) {
            is AzulProtocol.Event.Snapshot -> {
                // **局面只信服务器广播的**（落款为 null）。带落款 = 经中转来的，谁发的都不认：
                // 否则别的玩家可以伪造一份棋盘推给你。
                if (from != null) return
                if (event.version != AzulProtocol.VERSION) return
                applySnapshot(event.game)
            }

            // 拒绝只是一句提示、不带任何权威，谁发都收（最多被烦到）
            is AzulProtocol.Event.Rejected -> _state.update { it.copy(note = event.reason) }
        }
    }

    override fun onLinkClosed(reason: String?) {
        // 还没到点的"开下一轮"也跟着取消：链路没了就别再算
        scope.cancel()
        settleJob = null
        _state.update { it.copy(note = it.note ?: reason ?: "连接断了") }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 服务器注册频道时开局：准备 + 开始，然后推一份。 */
    private fun startServerGame(order: List<String>) {
        val engine = engine ?: return
        val failure = runCatching {
            engine.prepareNewGame(order.toSet())
            engine.startGame(order)
        }.exceptionOrNull()

        if (failure != null) {
            // 引擎没开起来也要让人看见原因（这是 bug 该暴露，不该静默）
            _state.update { it.copy(note = "对局没开起来：${failure.message}") }
            return
        }
        publish()
    }

    /** @return 没生效的原因；`null` = 生效了 */
    private fun apply(playerId: String, command: AzulProtocol.Command): String? {
        val engine = engine ?: return null
        val snapshot = engine.state.value

        val failure = runCatching {
            when (command) {
                is AzulProtocol.Command.TakeFromPlate -> {
                    if (snapshot.actPlayer != playerId) return "还没轮到你"
                    engine.selectTilesFromPlateToRow(command.tile, command.plate, playerId, command.row)
                }

                is AzulProtocol.Command.TakeFromRedundant -> {
                    if (snapshot.actPlayer != playerId) return "还没轮到你"
                    engine.selectTilesFromRedundantToRow(command.tile, playerId, command.row)
                }

                AzulProtocol.Command.Sync -> return null // 上面已经处理过
            }
        }.exceptionOrNull()

        return failure?.let { it.message ?: "这步走不了" }
    }

    /**
     * 结算画面上停 [roundSettleMs] 毫秒，然后自动 [AzulEngine.nextRoundOrFinishGame]。
     *
     * 这段时间里局面是 `RoundScore`：谁再发 take 都会被引擎挡下（"必须在 RoundPlaying 执行"），
     * 所以不需要额外的"锁"字段。链路一关就取消，别对着死链路算分。
     */
    private fun scheduleNextRound() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(roundSettleMs)
            synchronized(lock) {
                val engine = engine ?: return@synchronized
                runCatching { engine.nextRoundOrFinishGame() }
                publish()
            }
        }
    }

    private fun publish() {
        val engine = engine ?: return
        val snapshot = engine.state.value
        val payload = AzulProtocol.snapshot(snapshot)
        out?.broadcast(payload)
        // 服务器自己那份也直接更新：别指望回环那条 socket 的时序
        applySnapshot(snapshot)
    }

    private fun applySnapshot(snapshot: AzulSnapshot) {
        _state.update { it.copy(snapshot = snapshot, note = null) }
    }

    companion object {
        /** 结算画面停留多久再自动开下一轮：够看清谁扣了分，又不至于让人等。 */
        const val ROUND_SETTLE_MS = 1_500L
    }
}
