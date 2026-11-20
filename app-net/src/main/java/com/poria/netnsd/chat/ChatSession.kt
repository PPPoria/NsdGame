package com.poria.netnsd.chat

import android.content.Context
import com.poria.net.CloseCause
import com.poria.net.Peer
import com.poria.net.PeerListener
import com.poria.net.Tcp
import com.poria.net.TcpServer
import com.poria.netnsd.FoundHost
import com.poria.netnsd.ServiceAdvertiser
import java.io.IOException
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
 * 一个聊天室会话：把 `:core` 的连接、NSD 的端口广播、[ChatRoom] 的房间规则缝在一起，
 * 对外只暴露一个 [state]（给 UI 收）和 [say] / [close]。
 *
 * 两种入口，但对 UI **没有区别**：
 * ```
 * ChatSession.host(context, "小明的房间", "小明")   // 开房：TcpServer + 广播端口 + 自己回环连上来
 * ChatSession.guest(foundHost, "小红")              // 加入：连别人的 IP:端口
 * ```
 *
 * 房主自己也走 `Tcp.connect("127.0.0.1", ...)`，所以**没有任何 "我是房主" 的分支**：
 * 房主的 UI 拿到的同样是"客户端"这条路，房主特权（这里没有）应该在服务端侧校验。
 *
 * 线程：构造即返回，建房间/连接都在内部协程里（[Dispatchers.IO]），失败写进 `state.note`。
 * 唯一必须在主线程调用的是 [close]（[ServiceAdvertiser] 需要 Looper）。
 */
class ChatSession private constructor(
    private val playerName: String,
    private val isHost: Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ChatState(host = isHost))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** 我自己那条客户端连接（房主是回环、客人是真网络）。 */
    @Volatile
    private var me: Peer? = null

    // 房主侧：服务端 + 端口广播 + 房间规则
    private var server: TcpServer? = null
    private var advertiser: ServiceAdvertiser? = null
    private var room: ChatRoom? = null

    /** 连接 → 座位。多条连接的读线程会同时进来，所以要锁。 */
    private val seatOf = HashMap<Peer, Int>()
    private val seatLock = Any()

    /** 说一句话。真的发出去是服务端回显后才算数（见 [ChatRoom.say]）。 */
    fun say(text: String) {
        if (text.isBlank()) return
        me?.send(ChatProtocol.say(text))
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
                val newRoom = ChatRoom(roomName).also { room = it }

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

                _state.update { it.copy(roomName = newRoom.roomName, note = "房间已开好，等玩家进来…") }

                // 房主自己也是客户端：走回环，和远端客人同一条代码路径
                val connected = Tcp.connect(LOOPBACK, newServer.localPort, clientListener)
                if (scope.isActive) me = connected else connected.close()
            } catch (e: Exception) {
                _state.update { it.copy(phase = ChatPhase.CLOSED, note = "开房失败：${e.message}") }
            }
        }
    }

    private val advertiseListener = object : ServiceAdvertiser.Listener {
        override fun onFailed(reason: String) {
            _state.update { it.copy(note = reason) }
        }
    }

    /** 服务端侧：处理 JOIN / SAY，并维护"连接 → 座位"。 */
    private val serverListener = object : PeerListener {

        override fun onLine(peer: Peer, line: String) {
            val room = room ?: return
            when (val command = ChatProtocol.decodeCommand(line)) {
                is ChatCommand.Join -> when (val result = room.join(command.name) { peer.send(it) }) {
                    is ChatRoom.JoinResult.Joined -> synchronized(seatLock) { seatOf[peer] = result.seat }
                    ChatRoom.JoinResult.Full -> peer.send(ChatProtocol.error("房间满了"))
                    ChatRoom.JoinResult.DuplicateName -> peer.send(ChatProtocol.error("昵称被占了，换一个"))
                    ChatRoom.JoinResult.InvalidName -> peer.send(ChatProtocol.error("昵称不能为空"))
                }

                is ChatCommand.Say -> {
                    val seat = synchronized(seatLock) { seatOf[peer] } ?: return
                    room.say(seat, command.text)
                }

                null -> peer.send(ChatProtocol.error("看不懂的命令"))
            }
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            val seat = synchronized(seatLock) { seatOf.remove(peer) } ?: return
            room?.leave(seat)
        }
    }

    // ── 加入 ────────────────────────────────────────────────────────────────

    private fun startGuest(target: FoundHost) {
        _state.update { it.copy(roomName = target.serviceName, note = "正在连 ${target.serviceName}…") }
        scope.launch {
            try {
                val connected = Tcp.connect(target.host, target.port, clientListener)
                if (scope.isActive) me = connected else connected.close()
            } catch (e: IOException) {
                _state.update { it.copy(phase = ChatPhase.CLOSED, note = "连不上 ${target.serviceName}：${e.message}") }
            }
        }
    }

    /**
     * 客户端侧：房主和客人共用。`onOpen` 就报到，`onLine` 只认服务端事件。
     */
    private val clientListener = object : PeerListener {

        override fun onOpen(peer: Peer) {
            peer.send(ChatProtocol.join(playerName))
        }

        override fun onLine(peer: Peer, line: String) {
            val event = ChatProtocol.decodeEvent(line) ?: return // 不认识的直接忽略，别炸
            when (event) {
                is ChatEvent.Welcome -> _state.update {
                    it.copy(phase = ChatPhase.ONLINE, roomName = event.roomName, mySeat = event.seat, note = null)
                }

                is ChatEvent.Joined -> _state.update { state ->
                    val members = (state.members.filterNot { it.seat == event.seat } + ChatMember(event.seat, event.name))
                        .sortedBy { it.seat }
                    state.copy(members = members)
                }

                is ChatEvent.Left -> _state.update { state ->
                    state.copy(members = state.members.filterNot { it.seat == event.seat })
                }

                is ChatEvent.Log -> _state.update { state ->
                    val sender = state.members.firstOrNull { it.seat == event.seat }?.name ?: "#${event.seat}"
                    state.copy(messages = state.messages + ChatMessage(sender, event.text, event.seat == state.mySeat))
                }

                is ChatEvent.Error -> _state.update { it.copy(note = event.reason) }
            }
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            _state.update {
                it.copy(phase = ChatPhase.CLOSED, note = reason ?: "连接已断开（$cause）")
            }
        }
    }

    companion object {

        private const val LOOPBACK = "127.0.0.1"

        /** 开房。立刻返回，建好了会有 `state`。 */
        fun host(context: Context, roomName: String, playerName: String): ChatSession =
            ChatSession(playerName, isHost = true).apply { startHost(context, roomName) }

        /** 加入别人搜到的房间。立刻返回，连上了会有 `state`。 */
        fun guest(target: FoundHost, playerName: String): ChatSession =
            ChatSession(playerName, isHost = false).apply { startGuest(target) }
    }
}
