package com.ppporia.nsdgames.viewmodel.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poria.game.GameCatalog
import com.poria.netnsd.Session
import com.poria.netnsd.blob.imageFile
import com.poria.netnsd.blob.requestImage
import com.poria.netnsd.link.TcpLink
import com.poria.netnsd.lobby.LobbyPhase
import com.poria.netnsd.lobby.LobbySnapshot
import com.poria.netnsd.lobby.LobbyState
import com.poria.netnsd.lobby.SessionPhase
import com.poria.netnsd.lobby.kick
import com.poria.netnsd.lobby.mergedWith
import com.poria.netnsd.lobby.say
import com.poria.netnsd.lobby.selectGame
import com.poria.netnsd.lobby.setOption
import com.poria.netnsd.lobby.setReady
import com.poria.netnsd.lobby.setTableSize
import com.poria.netnsd.lobby.start
import com.poria.netnsd.lobby.swapSeats
import com.poria.netnsd.lobby.takeSeat
import com.poria.netnsd.profile.Profile
import com.ppporia.nsdgames.App
import com.ppporia.nsdgames.ui.room.RoomMessageItem
import com.ppporia.nsdgames.ui.room.messagesOf
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 房间页的 ViewModel：**把几个频道的流拼起来、拍平成 UI 状态**，并把动作转给会话。
 *
 * - 连接不归它管：链路和频道在 `App.session.connection` 上，【这个页面退出不会断开连接】
 *   （所以"房间 → 对局"换页时链路还活着，回大厅也就是退了对局页而已）；
 * - 拼装就在这儿做：`lobby` 的权威快照 + `profile` 的资料表 + 链路状态 + 已经能画的图，
 *   四个频道谁都不用认识对方（**房间只给 id 和座位，名字头像来自 profile**）；
 * - 动作只是一行转发（`lobby/LobbyCommands.kt` 里的扩展函数），不返回结果——
 *   成功与否由服务器推回的新快照说了算；
 * - 资料里谁有头像而本机还没有，在 [init] 里统一按 id 去要（`requestImage` 自带去重）。
 *
 * **客户端不存房间数据**：名册 / 座位 / 设置 / 消息全部来自 `lobby.state.snapshot`。
 */
class RoomVM : ViewModel() {

    /** 进程里唯一的会话（`App` 的伴生对象）；这里只是引用，不负责它的生死。 */
    private val session = App.session

    private val connection = session.connection

    private val source: StateFlow<RoomSource> = if (connection == null) {
        MutableStateFlow(RoomSource.EMPTY)
    } else {
        val start = RoomSource(
            lobby = connection.initialState,
            profiles = connection.profile.profiles.value,
            link = connection.link.state.value,
            images = connection.blob.available.value,
        )
        combine(
            connection.lobby.state,
            connection.profile.profiles,
            connection.link.state,
            connection.blob.available,
        ) { lobby, profiles, link, images ->
            RoomSource(lobby.mergedWith(link, images), profiles, link, images)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = start,
        )
    }

    val state: StateFlow<RoomUiState> = source
        .map { it.toUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = source.value.toUiState(),
        )

    /**
     * 房主按了开始、大厅切进对局阶段时发一次：Activity 收到就去开对局页。
     *
     * 用一次性事件而不是"状态里放个开关"：否则从对局页退回来时大厅状态还是 `Playing`，
     * 会立刻又被弹回对局页。
     */
    private val _startGame = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val startGame: SharedFlow<Unit> = _startGame

    init {
        viewModelScope.launch {
            var lastPhase = source.value.lobby.snapshot?.phase ?: LobbyPhase.Lobby
            source.collect { source ->
                requestImages(source.profiles)
                requestProfiles(source)

                val phase = source.lobby.snapshot?.phase ?: LobbyPhase.Lobby
                if (phase == LobbyPhase.Playing && lastPhase != LobbyPhase.Playing) {
                    _startGame.tryEmit(Unit)
                }
                lastPhase = phase
            }
        }
    }

    // ── 动作（都只是发命令，结果以服务器推回的快照为准）────────────────────

    fun say(text: String) {
        session.say(text)
    }

    fun setReady(ready: Boolean) {
        session.setReady(ready)
    }

    /** 准备按钮：把"当前状态取反"作为请求发出去，真正生效以快照为准。 */
    fun toggleReady() {
        session.setReady(!source.value.lobby.iAmReady)
    }

    fun start() {
        session.start()
    }

    fun selectGame(gameId: String) {
        session.selectGame(gameId)
    }

    fun setTableSize(size: Int) {
        session.setTableSize(size)
    }

    fun setOption(optionId: String, on: Boolean) {
        session.setOption(optionId, on)
    }

    fun takeSeat(seat: Int) {
        session.takeSeat(seat)
    }

    fun swapSeats(a: Int, b: Int) {
        session.swapSeats(a, b)
    }

    fun kick(playerId: String) {
        session.kick(playerId)
    }

    /**
     * 资料里带了头像 id 的，本机还没有就去要。
     *
     * **谁是拥有者由资料说了算**（`Profile` 是按 id 存的，key 就是本人），
     * 不再需要从房间快照里猜——头像晚到也不会卡住房间。
     */
    private fun requestImages(profiles: Map<String, Profile>) {
        profiles.forEach { (ownerId, profile) ->
            if (profile.avatarId.isNotEmpty()) {
                session.requestImage(profile.avatarId, ownerId)
            }
        }
    }

    /**
     * 名册里还有谁没资料？按 id 去问。
     *
     * **对方可以不答**（不想公开就是不回，座位条上一直显示占位）——
     * `ProfileChannel` 会记住"已经问过"，名册怎么变都不会变成请求风暴。
     */
    private fun requestProfiles(source: RoomSource) {
        val roster = source.lobby.snapshot?.players?.map { it.id }.orEmpty()
        session.connection?.profile?.sync(roster)
    }

    // ── 拼装 ────────────────────────────────────────────────────────────────

    /** 四个频道各取一份，UI 只吃这一份。 */
    private data class RoomSource(
        val lobby: LobbyState,
        val profiles: Map<String, Profile>,
        val link: TcpLink.LinkState,
        val images: Set<String>,
    ) {
        companion object {
            val EMPTY = RoomSource(LobbyState(), emptyMap(), TcpLink.LinkState(), emptySet())
        }
    }

    /**
     * 把几股流拍平成 UI 状态。
     *
     * **图片也在这里解析掉**（`SeatUi.avatar` 直接是文件）：`state` 是 StateFlow，会按 equals 去重，
     * 所以"渲染要用到的每一样东西"都必须进 [RoomUiState]——不然图下完了、状态没变，
     * 这次更新会被悄悄吞掉（症状就是"第一次拉头像要等下一次 UI 刷新才出现"）。
     */
    private fun RoomSource.toUiState(): RoomUiState {
        val snapshot = lobby.snapshot
        val game = snapshot?.let { GameCatalog.byId(it.gameId) }
        return RoomUiState(
            title = snapshot?.roomName.orEmpty(),
            gameTitle = game?.title.orEmpty(),
            gameKnown = game != null,
            playerCount = snapshot?.players?.size ?: 0,
            tableSize = snapshot?.tableSize ?: 0,
            phase = lobby.phase,
            note = lobby.note,
            hostless = snapshot != null && snapshot.hostId.isEmpty(),
            online = lobby.online,
            host = lobby.iAmHost,
            ready = lobby.iAmReady,
            startHint = snapshot?.startHint.orEmpty(),
            lobbyPhase = snapshot?.phase ?: LobbyPhase.Lobby,
            seats = snapshot?.seatModels(lobby.myId, profiles).orEmpty(),
            messages = messagesOf(snapshot, profiles, lobby.myId),
            myId = lobby.myId,
            names = profiles.mapValues { (_, profile) -> profile.name.ifEmpty { UNKNOWN_NAME } },
            snapshot = snapshot,
        )
    }

    /** 座位号 = 出手顺序；空位也占一个位子。头像在这里就换成文件（没有就是 `null`）。 */
    private fun LobbySnapshot.seatModels(
        myId: String,
        profiles: Map<String, Profile>,
    ): List<SeatUi> = (0 until tableSize).map { seat ->
        val player = playerAt(seat)
        val profile = player?.let { profiles[it.id] }
        SeatUi(
            seat = seat,
            occupied = player != null,
            name = when {
                player == null -> ""
                profile == null || profile.name.isEmpty() -> UNKNOWN_NAME
                else -> profile.name
            },
            tag = when {
                player == null -> SeatTag.NONE
                isHost(player.id) -> SeatTag.HOST
                isReady(player.id) -> SeatTag.READY
                else -> SeatTag.WAITING
            },
            mine = player != null && player.id == myId,
            avatar = profile?.avatarId?.takeIf { it.isNotEmpty() }?.let(session::imageFile),
        )
    }

    companion object {
        /** 名册里有人、但资料还没到时的占位（不是空座，是"人在、资料在路上"）。 */
        const val UNKNOWN_NAME = "…"
    }
}

/** UI 要渲染的房间状态：由 [RoomVM] 拼装 + 拍平而来，客户端不自己攒。 */
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
    /** 大厅处在哪个阶段；切到 `Playing` 之后这里的所有操作都会被服务器拒。 */
    val lobbyPhase: LobbyPhase = LobbyPhase.Lobby,
    val seats: List<SeatUi> = emptyList(),
    val messages: List<RoomMessageItem> = emptyList(),
    val myId: String = "",
    /** id → 昵称（座位面板要按 id 查名字，名字来自 profile 频道）。 */
    val names: Map<String, String> = emptyMap(),
    /** 设置面板要用的原始快照（它就是数据本源，不再转一层）。 */
    val snapshot: LobbySnapshot? = null,
) {
    /** 收到过快照、而且还在大厅阶段才能操作（按钮是否可用）。 */
    val canAct: Boolean get() = online && snapshot != null && lobbyPhase == LobbyPhase.Lobby

    fun nameOf(id: String): String = names[id] ?: RoomVM.UNKNOWN_NAME
}

/**
 * 座位条上的一格。
 *
 * [occupied] 和 [name] 是两件事：座位上有人（快照说的）和资料到没到（profile 频道说的）。
 * 人在、资料还没到时 [name] 是 `…`，不是空位。
 *
 * [avatar] 是**已经解析好的图片文件**（VM 查的），没下载完就是 `null`。
 * 它必须在状态里，否则"图下完了"这件事不会让 [RoomUiState] 发生变化——
 * `stateIn` 的 StateFlow 按 equals 去重，那次更新就被吞了。
 */
data class SeatUi(
    val seat: Int,
    val occupied: Boolean,
    val name: String,
    val tag: SeatTag,
    val mine: Boolean,
    val avatar: File?,
)

enum class SeatTag { NONE, HOST, READY, WAITING }
