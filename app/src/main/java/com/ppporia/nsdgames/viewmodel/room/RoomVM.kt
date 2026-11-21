package com.ppporia.nsdgames.viewmodel.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poria.core.game.GameCatalog
import com.poria.netnsd.room.RoomSession
import com.poria.netnsd.room.RoomSnapshot
import com.poria.netnsd.room.RoomState
import com.poria.netnsd.room.SessionPhase
import com.ppporia.nsdgames.ui.room.RoomMessageItem
import com.ppporia.nsdgames.ui.room.messagesOf
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 房间页的 ViewModel：**会话和快照都归它管**，Activity 只管画和点。
 *
 * - `session` 在构造时由 `RoomActivity` 通过工厂传进来（开房 / 加入的差别在这里**看不见**，
 *   两条路拿到的都是同一个 [RoomSession]）；
 * - 对外只有 [state]（UI 直接 `collect`）和一组"发命令"的方法，方法不返回结果——
 *   成功与否由服务端推回的新快照说了算；
 * - 快照里谁有头像而本机还没有，在 [init] 里统一按 id 去要（`RoomSession.requestBlob` 自带去重），
 *   Activity 不用在每次重画时做这种副作用；
 * - 页面销毁时在 [onCleared] 关会话（`close()` 必须在主线程，`onCleared` 就是主线程；
 *   转屏重建时 ViewModel 不会清，会话也不会被误关）。
 *
 * **客户端不存房间数据**：花名册 / 座位 / 设置 / 消息全部来自 `session.state.snapshot`，
 * [RoomUiState] 只是把它拍平成"UI 好摆"的形状（座位号→座位、消息→带发送者昵称的行）。
 */
class RoomVM(private val session: RoomSession?) : ViewModel() {

    private val source: StateFlow<RoomState> = session?.state ?: MutableStateFlow(RoomState())

    val state: StateFlow<RoomUiState> = source
        .map { it.toUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = source.value.toUiState(),
        )

    init {
        viewModelScope.launch {
            session?.state?.collect { room -> room.snapshot?.let(::requestAvatars) }
        }
    }

    // ── 动作（都只是发命令，结果以服务端推回的快照为准）────────────────────

    fun say(text: String) {
        session?.say(text)
    }

    fun setReady(ready: Boolean) {
        session?.setReady(ready)
    }

    /** 准备按钮：把"当前状态取反"作为请求发出去，真正生效以快照为准。 */
    fun toggleReady() {
        val room = session ?: return
        room.setReady(!room.state.value.iAmReady)
    }

    fun start() {
        session?.start()
    }

    fun selectGame(gameId: String) {
        session?.selectGame(gameId)
    }

    fun setTableSize(size: Int) {
        session?.setTableSize(size)
    }

    fun setOption(optionId: String, on: Boolean) {
        session?.setOption(optionId, on)
    }

    fun takeSeat(seat: Int) {
        session?.takeSeat(seat)
    }

    fun swapSeats(a: Int, b: Int) {
        session?.swapSeats(a, b)
    }

    fun kick(playerId: String) {
        session?.kick(playerId)
    }

    /** 缓存里可用的头像文件；没有就返回 `null`——UI 先画占位，等 `availableBlobs` 推一次再来取。 */
    fun blobFile(id: String): File? = session?.blobFile(id)

    override fun onCleared() {
        // close() 必须在主线程：它要调 NsdManager 注销广播。onCleared 就是主线程。
        session?.close()
        super.onCleared()
    }

    // ── 状态拍平 ────────────────────────────────────────────────────────────

    private fun requestAvatars(snapshot: RoomSnapshot) {
        val room = session ?: return
        snapshot.players.forEach { player ->
            if (player.avatar.isNotEmpty()) room.requestBlob(player.avatar)
        }
    }

    private fun RoomState.toUiState(): RoomUiState {
        val snapshot = snapshot
        val game = snapshot?.let { GameCatalog.byId(it.gameId) }
        return RoomUiState(
            title = snapshot?.roomName.orEmpty(),
            gameTitle = game?.title.orEmpty(),
            gameKnown = game != null,
            playerCount = snapshot?.players?.size ?: 0,
            tableSize = snapshot?.tableSize ?: 0,
            phase = phase,
            note = note,
            hostless = snapshot != null && snapshot.hostId.isEmpty(),
            online = online,
            host = iAmHost,
            ready = iAmReady,
            startHint = snapshot?.startHint.orEmpty(),
            seats = snapshot?.seatModels(myId).orEmpty(),
            messages = messagesOf(snapshot, myId),
            myId = myId,
            snapshot = snapshot,
        )
    }

    /** 座位号 = 出手顺序；空位也占一个位子（`name = null`）。 */
    private fun RoomSnapshot.seatModels(myId: String): List<SeatUi> =
        (0 until tableSize).map { seat ->
            val player = playerAt(seat)
            SeatUi(
                seat = seat,
                name = player?.name,
                tag = when {
                    player == null -> SeatTag.NONE
                    player.host -> SeatTag.HOST
                    player.ready -> SeatTag.READY
                    else -> SeatTag.WAITING
                },
                mine = player != null && player.id == myId,
                avatarId = player?.avatar.orEmpty(),
            )
        }
}

/** UI 要渲染的房间状态：由 [RoomVM] 从服务端快照拍平而来，客户端不自己攒。 */
data class RoomUiState(
    val title: String = "",
    val gameTitle: String = "",
    val gameKnown: Boolean = false,
    val playerCount: Int = 0,
    val tableSize: Int = 0,
    val phase: SessionPhase = SessionPhase.CONNECTING,
    val note: String? = null,
    /** 房间里暂时没有房主（房主那条连接断了）。 */
    val hostless: Boolean = false,
    val online: Boolean = false,
    /** 我是不是房主——由服务端快照说的，不是本机猜的。 */
    val host: Boolean = false,
    /** 我准备好了没。 */
    val ready: Boolean = false,
    /** 服务端算的"还不能开局"的原因；空串 = 能开。 */
    val startHint: String = "",
    val seats: List<SeatUi> = emptyList(),
    val messages: List<RoomMessageItem> = emptyList(),
    val myId: String = "",
    /** 设置面板要用的原始快照（它就是数据本源，不再转一层）。 */
    val snapshot: RoomSnapshot? = null,
) {
    /** 收到过快照才能操作（按钮是否可用）。 */
    val canAct: Boolean get() = online && snapshot != null
}

/** 座位条上的一格。[name] 为 `null` 表示空位。 */
data class SeatUi(
    val seat: Int,
    val name: String?,
    val tag: SeatTag,
    val mine: Boolean,
    val avatarId: String,
)

enum class SeatTag { NONE, HOST, READY, WAITING }
