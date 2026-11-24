package com.ppporia.nsdgames.viewmodel.azul

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poria.game.azul.AzulPhase
import com.poria.game.azul.AzulSnapshot
import com.poria.game.azul.Tile
import com.poria.netnsd.azul.AzulChannel
import com.poria.netnsd.azul.AzulGameState
import com.poria.netnsd.azul.ensureAzul
import com.poria.netnsd.azul.takeFromPlate
import com.poria.netnsd.azul.takeFromRedundant
import com.poria.netnsd.blob.imageFile
import com.poria.netnsd.blob.requestImage
import com.poria.netnsd.profile.Profile
import com.ppporia.nsdgames.App
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 对局页的 ViewModel：**注册 `azul` 频道 + 拼装 UI 状态 + 转发动作**。
 *
 * - 连接还是那条（`App.session`），这里只多挂一个频道：
 *   **房主那台机器的实例带着引擎**（服务器半边），别人的是纯客户端半边；
 * - 频道在 `init` 里 `register`、`onCleared` 里 `unregister`——**绝不关链路**，
 *   退出对局页还能回大厅（大厅的 `Lobby`、资料、图片都还在）；
 * - 局面只有一份真相：服务器推的 `AzulSnapshot`；规则在 `:game-core` 的 `AzulEngine` 里，
 *   这个 VM **一行判定都不写**（`myTurn` 只是给 UI 提示，真校验在服务器）；
 * - 名字和头像还是 `profile` 频道给的，和房间页同一套查法。
 */
class AzulGameVM : ViewModel() {

    private val session = App.session
    private val connection = session.connection

    /**
     * 对局频道：**由 `Session` 持有**（`ensureAzul()` 幂等），页面只是订阅者。
     *
     * 页面退出**不会**关它——房主那台机子上的引擎就挂在这个实例上，关掉等于把全桌牌桌砸了。
     * `null` = 没连接 / 大厅还没切进对局阶段。
     */
    private val channel: AzulChannel? = session.ensureAzul()

    private val profiles: StateFlow<Map<String, Profile>> =
        connection?.profile?.profiles ?: MutableStateFlow(emptyMap())

    /** 图片可用性只是为了让"头像下完了"也触发一次 UI 更新（`state` 是 StateFlow，会按 equals 去重）。 */
    private val images: StateFlow<Set<String>> =
        connection?.blob?.available ?: MutableStateFlow(emptySet())

    private val gameState: StateFlow<AzulGameState> =
        channel?.state ?: MutableStateFlow(AzulGameState())

    /** 座次（座位号 = 出手顺序）：从大厅快照读，用来给玩家排序。 */
    private val seatIndex: Map<String, Int> =
        connection?.lobby?.state?.value?.snapshot?.players?.associate { it.id to it.seat }.orEmpty()

    val state: StateFlow<AzulGameUiState> = combine(
        gameState,
        profiles,
        images,
    ) { game, known, _ -> game.toUiState(known) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = gameState.value.toUiState(profiles.value),
        )

    init {
        viewModelScope.launch {
            profiles.collect { known -> requestImages(known) }
        }
    }

    // ── 动作（发命令，结果以服务器推回来的局面为准）──────────────────────────

    /** 从工厂盘拿砖：[plate] 必须是那个盘子上的全部砖（服务器靠它认盘子）。 */
    fun takeFromPlate(row: Int, tile: Tile, plate: List<Tile>) {
        session.takeFromPlate(row, tile, plate)
    }

    fun takeFromRedundant(row: Int, tile: Tile) {
        session.takeFromRedundant(row, tile)
    }

    // 没有"下一轮"这类动作：一轮结束 / 开下一轮由服务器的引擎自己判（`nowCanFinishRound`），
    // 客户端只负责把每次 take 发出去、把推回来的局面画出来。

    override fun onCleared() {
        // **不注销频道、更不关链路**：对局频道跟着连接活（房间/引擎/局面都还要用），
        // 回首页时 `session.leave()` 才统一收摊。
        super.onCleared()
    }

    // ── 拼装 ────────────────────────────────────────────────────────────────

    /** 资料里带了头像 id 而本机还没有，就去要（拥有者就是资料表的 key）。 */
    private fun requestImages(known: Map<String, Profile>) {
        known.forEach { (ownerId, profile) ->
            if (profile.avatarId.isNotEmpty()) {
                session.requestImage(profile.avatarId, ownerId)
            }
        }
    }

    private fun AzulGameState.toUiState(known: Map<String, Profile>): AzulGameUiState {
        val snapshot = snapshot
        return AzulGameUiState(
            online = online,
            myId = myId,
            myTurn = myTurn,
            phase = phase,
            note = note,
            snapshot = snapshot,
            players = snapshot?.desktop?.players
                ?.sortedBy { id -> seatIndex[id] ?: Int.MAX_VALUE }
                ?.map { id -> playerOf(snapshot, id, known) }
                .orEmpty(),
        )
    }

    private fun playerOf(
        snapshot: AzulSnapshot,
        id: String,
        known: Map<String, Profile>,
    ): AzulPlayerUi {
        val profile = known[id]
        return AzulPlayerUi(
            id = id,
            name = profile?.name?.ifEmpty { null } ?: UNKNOWN_NAME,
            avatar = profile?.avatarId?.takeIf { it.isNotEmpty() }?.let(session::imageFile),
            score = snapshot.desktop.workspaces[id]?.score ?: 0,
            act = snapshot.actPlayer == id,
            mine = id == gameState.value.myId,
        )
    }

    companion object {
        /** 人在、资料还没到时的占位（和房间页一致）。 */
        const val UNKNOWN_NAME = "…"
    }
}

/** 对局页要渲染的东西：服务器推来的局面 + 拼好的玩家条目。 */
data class AzulGameUiState(
    /** 收到过局面才能操作。 */
    val online: Boolean = false,
    val myId: String = "",
    /** 是不是该我动（**只用于 UI 提示**，真正的校验在服务器）。 */
    val myTurn: Boolean = false,
    val phase: AzulPhase = AzulPhase.Preparing,
    /** 被拒绝的原因 / 连接断了。 */
    val note: String? = null,
    /** 整份局面：工厂盘、中央区、各家墙和地板都在里面（Activity 直接读它画）。 */
    val snapshot: AzulSnapshot? = null,
    /** 按座次排好的玩家：昵称、头像、分数、是不是该他动。 */
    val players: List<AzulPlayerUi> = emptyList(),
) {
    val canAct: Boolean get() = online && snapshot != null
}

data class AzulPlayerUi(
    val id: String,
    val name: String,
    val avatar: File?,
    val score: Int,
    /** 当前该他动。 */
    val act: Boolean,
    val mine: Boolean,
)
