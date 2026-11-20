package com.poria.netnsd.room

import android.content.Context
import com.poria.net.CloseCause
import com.poria.net.Peer
import com.poria.net.PeerListener
import com.poria.net.Tcp
import com.poria.net.TcpServer
import com.poria.netnsd.FoundHost
import com.poria.netnsd.ServiceAdvertiser
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个"游戏准备房间"会话：把 `:core` 的连接、NSD 的端口广播、[Room] 的房间规则缝在一起，
 * 对外只有 [state]（给 UI 收）和一串"发命令"的方法。
 *
 * ```
 * RoomSession.host(context, "小明的房间", "小明")   // 开房：TcpServer + 广播端口 + 自己回环连上来
 * RoomSession.guest(foundHost, "小红")              // 加入：连别人的 IP:端口
 * ```
 *
 * 两条路对 UI **没有区别**：房主自己也走 `Tcp.connect("127.0.0.1", …)`，拿到的是同一个
 * [RoomState]。"我是房主"这件事只有两个地方知道——服务端按回环地址认人（[Room.join] 的
 * `host` 参数），快照里的 `hostId` 再告诉客户端该不该显示房主功能。
 *
 * **客户端不存状态**：这里没有花名册、没有座位表，只有一个"最近收到的快照"
 * （[RoomState.snapshot]）。发命令也不会先改本地——等快照回来再说。
 *
 * 线程：构造即返回，建房间/连接都在内部协程里（[Dispatchers.IO]），失败写进 `state.note`。
 * 唯一必须在主线程调用的是 [close]（[ServiceAdvertiser] 要 Looper）。
 */
class RoomSession private constructor(
    private val playerName: String,
    private val isHost: Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 本机身份。客户端自己生成，服务端认可后就是这间房里的 playerId。 */
    private val myId: String = UUID.randomUUID().toString().take(RoomProtocol.MAX_ID)

    private val _state = MutableStateFlow(RoomState(host = isHost, myId = myId))
    val state: StateFlow<RoomState> = _state.asStateFlow()

    /** 我自己那条客户端连接（房主是回环、客人是真网络）。 */
    @Volatile
    private var me: Peer? = null

    // 房主侧：服务端 + 端口广播 + 房间规则
    private var server: TcpServer? = null
    private var advertiser: ServiceAdvertiser? = null
    private var room: Room? = null

    /** 连接 → playerId。多条连接的读线程会同时进来，所以要锁。 */
    private val players = HashMap<Peer, String>()
    private val playerLock = Any()

    // ── 命令（都是"发出去就不管了"，结果以服务端推回的快照为准）──────────────

    fun say(text: String) {
        if (text.isBlank()) return
        me?.send(RoomProtocol.say(text))
    }

    fun setReady(ready: Boolean) {
        me?.send(RoomProtocol.ready(ready))
    }

    fun takeSeat(seat: Int) {
        me?.send(RoomProtocol.takeSeat(seat))
    }

    fun swapSeats(a: Int, b: Int) {
        me?.send(RoomProtocol.swapSeats(a, b))
    }

    fun setTableSize(size: Int) {
        me?.send(RoomProtocol.setTableSize(size))
    }

    fun selectGame(gameId: String) {
        me?.send(RoomProtocol.selectGame(gameId))
    }

    fun setOption(optionId: String, on: Boolean) {
        me?.send(RoomProtocol.setOption(optionId, on))
    }

    fun kick(playerId: String) {
        me?.send(RoomProtocol.kick(playerId))
    }

    fun start() {
        me?.send(RoomProtocol.start())
    }

    /**
     * 收摊：断开自己那条、停广播、停服务端、取消所有协程。
     *
     * **必须在主线程调用**（`ServiceAdvertiser` 要 Looper）。Activity 的 `onDestroy` 正合适。
     * 重复调用安全。
     */
    fun close() {
        runCatching { me?.close() }
        me = null
        runCatching { advertiser?.unregister() }
        advertiser = null
        runCatching { server?.stop() }
        server = null
        room = null
        scope.cancel()
    }

    // ── 房主 ────────────────────────────────────────────────────────────────

    private fun startHost(context: Context, roomName: String) {
        scope.launch {
            try {
                val newRoom = Room(roomName).also { room = it }

                // 0 = 系统分配端口。start() 是同步绑定，所以拿到 localPort 之后才能广播
                val newServer = TcpServer(0, serverListener).start().also { server = it }
                if (!scope.isActive) return@launch // 建的过程中页面就退了，close() 已经收过 server

                // 广播端口要求主线程（NsdManager 要 Looper）
                val newAdvertiser = ServiceAdvertiser(context.applicationContext, advertiseListener)
                withContext(Dispatchers.Main.immediate) {
                    newAdvertiser.register(newRoom.roomName, newServer.localPort)
                }
                advertiser = newAdvertiser
                if (!scope.isActive) {
                    // close() 正好发生在注册过程中：撤销这条广播，否则房间会一直挂在局域网里
                    withContext(Dispatchers.Main.immediate) { newAdvertiser.unregister() }
                    return@launch
                }

                _state.update { it.copy(note = "房间已开好，等朋友进来…") }

                // 房主自己也是客户端：走回环，和远端客人同一条代码路径
                val connected = Tcp.connect(LOOPBACK, newServer.localPort, clientListener)
                if (scope.isActive) me = connected else connected.close()
            } catch (e: Exception) {
                _state.update { it.copy(phase = SessionPhase.CLOSED, note = "开房失败：${e.message}") }
            }
        }
    }

    private val advertiseListener = object : ServiceAdvertiser.Listener {
        override fun onFailed(reason: String) {
            _state.update { it.copy(note = reason) }
        }
    }

    /** 服务端侧：认人（JOIN）→ 逐条命令交给 [Room] → 出错的回一句 ERROR。 */
    private val serverListener = object : PeerListener {

        override fun onLine(peer: Peer, line: String) {
            val room = room ?: return
            val command = RoomProtocol.decodeCommand(line)
            if (command == null) {
                peer.send(RoomProtocol.error("看不懂的命令"))
                return
            }
            if (command is RoomCommand.Join) {
                onJoin(room, peer, command)
                return
            }

            val playerId = synchronized(playerLock) { players[peer] } ?: return
            val rejection = dispatch(room, playerId, command)
            if (rejection != null) peer.send(RoomProtocol.error(rejection))
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            val playerId = synchronized(playerLock) { players.remove(peer) } ?: return
            room?.leave(playerId)
        }
    }

    /**
     * 房主身份在这里认：**回环连上来那条就是房主**。
     *
     * 客户端自称"我是房主"没用——座位、设置这些命令的权限由 [Room] 按这个标记判。
     */
    private fun onJoin(room: Room, peer: Peer, command: RoomCommand.Join) {
        val result = room.join(
            playerId = command.playerId,
            name = command.name,
            host = peer.remoteHost == LOOPBACK,
            send = peer::send,
            close = peer::close,
        )
        when (result) {
            Room.JoinResult.Joined -> synchronized(playerLock) { players[peer] = command.playerId }
            Room.JoinResult.Full -> reject(peer, "牌桌坐满了")
            Room.JoinResult.DuplicateName -> reject(peer, "昵称被占了，换一个")
            Room.JoinResult.AlreadyJoined -> reject(peer, "你已经在这间房里了")
            Room.JoinResult.InvalidName -> reject(peer, "昵称不能为空")
            Room.JoinResult.InvalidId -> reject(peer, "客户端身份不合法")
        }
    }

    private fun reject(peer: Peer, reason: String) {
        peer.send(RoomProtocol.bye(reason))
        peer.close()
    }

    private fun dispatch(room: Room, playerId: String, command: RoomCommand): String? = when (command) {
        is RoomCommand.Join -> null
        is RoomCommand.Say -> room.say(playerId, command.text)
        is RoomCommand.Ready -> room.setReady(playerId, command.ready)
        is RoomCommand.TakeSeat -> room.takeSeat(playerId, command.seat)
        is RoomCommand.SwapSeats -> room.swapSeats(playerId, command.a, command.b)
        is RoomCommand.SetTableSize -> room.setTableSize(playerId, command.size)
        is RoomCommand.SelectGame -> room.selectGame(playerId, command.gameId)
        is RoomCommand.SetOption -> room.setOption(playerId, command.optionId, command.on)
        is RoomCommand.Kick -> room.kick(playerId, command.playerId)
        RoomCommand.Start -> room.start(playerId)
    }

    // ── 加入 ────────────────────────────────────────────────────────────────

    private fun startGuest(target: FoundHost) {
        _state.update { it.copy(note = "正在连 ${target.serviceName}…") }
        scope.launch {
            try {
                val connected = Tcp.connect(target.host, target.port, clientListener)
                if (scope.isActive) me = connected else connected.close()
            } catch (e: IOException) {
                _state.update {
                    it.copy(phase = SessionPhase.CLOSED, note = "连不上 ${target.serviceName}：${e.message}")
                }
            }
        }
    }

    /** 客户端侧：房主和客人共用。连上就报到，之后只认服务端推来的快照/错误/请离开。 */
    private val clientListener = object : PeerListener {

        override fun onOpen(peer: Peer) {
            peer.send(RoomProtocol.join(myId, playerName))
        }

        override fun onLine(peer: Peer, line: String) {
            val event = RoomProtocol.decodeEvent(line) ?: return // 不认识的直接忽略，别炸
            when (event) {
                is RoomEvent.Snapshot -> _state.update {
                    it.copy(phase = SessionPhase.ONLINE, snapshot = event.snapshot, note = null)
                }

                is RoomEvent.Error -> _state.update { it.copy(note = event.reason) }

                is RoomEvent.Bye -> _state.update {
                    it.copy(phase = SessionPhase.CLOSED, note = event.reason)
                }
            }
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            _state.update { state ->
                state.copy(
                    phase = SessionPhase.CLOSED,
                    note = state.note ?: reason ?: "连接已断开（$cause）",
                )
            }
        }
    }

    companion object {

        private const val LOOPBACK = "127.0.0.1"

        /** 开房。立刻返回，建好了会有 `state`。 */
        fun host(context: Context, roomName: String, playerName: String): RoomSession =
            RoomSession(playerName, isHost = true).apply { startHost(context, roomName) }

        /** 加入别人搜到的房间。立刻返回，连上了会有 `state`。 */
        fun guest(target: FoundHost, playerName: String): RoomSession =
            RoomSession(playerName, isHost = false).apply { startGuest(target) }
    }
}
