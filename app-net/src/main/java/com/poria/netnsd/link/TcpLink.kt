package com.poria.netnsd.link

import com.poria.net.CloseCause
import com.poria.net.Peer
import com.poria.net.PeerListener
import com.poria.net.Tcp
import com.poria.net.TcpServer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * 一条局域网链路：只管 **socket、peer 身份、路由**，不管规则、不管 NSD、不管 UI。
 *
 * ```
 * 房主：val port = link.host(0)          // 起 TcpServer + 自己走 127.0.0.1 回环连上来
 *                                      // 拿到的端口交给上层去 NSD 广播（链路不碰 Android）
 * 客人：link.join(ip, port)              // Tcp.connect
 *
 * 报到：连上就发 `_link` 的 hello（不带身份）→ 房主分一个 id 回 welcome
 *       → link.playerId 有值，各频道的 onLinked 也被叫一遍
 *
 * 收到一行：FRAME|<json>
 *   ns == "_link" → 链路自己处理（hello / welcome / ping / pong / bye）
 *   其它 ns       → 按频道找到注册的 Handler，把 p 原样交给它
 * ```
 *
 * **身份由房主分配**，不是客户端自己编的：这样"谁是谁"只有一份来源，
 * 上层（`Room`、将来的对局频道）拿到的永远是链路认可过的 id。
 *
 * 方向由"从哪条连接收到"决定，帧里没有"命令/推送"字段：
 * 服务端侧收到 → [TcpHandler.onCommand]（带是谁发的）；客户端侧收到 → [TcpHandler.onEvent]。
 *
 * ## 谁能造、谁能关（编译器管的，不是文档管的）
 * 构造器、[host]、[join]、[close]、[disconnect] 都是 `internal`：**只有 `:app-net` 里的
 * `Session` 能造链路、能启动它、能关掉它**。`:app` 的页面/VM 拿得到 `TcpLink` 引用，
 * 但只能读 [state] / [playerId]、`register` 自己的频道、发帧——没法自己开一条链路，
 * 也没法把房间关了。
 *
 * ## 线程
 * - [host] / [join] 是**阻塞**的（建 socket + 等房主分身份），别在主线程调；
 * - Handler 的回调在收到消息那条连接的**读线程**上按序执行，不同连接之间可能并发——
 *   Handler 自己保证线程安全（`synchronized` 一把锁，和 `Room` 同一个取舍）；
 * - 发送**不在链路锁里做**：先把 peer 列表拍个快照，再在锁外写 socket，
 *   免得一个卡死的对端把整条链路堵住。
 *
 * ## 心跳
 * 每 [beatIntervalMs] 给每条连接发一次 ping；超过 [timeoutMs] 没收到任何帧就断开那条连接
 * （局域网里手机直接消失不会有 FIN，只能靠应用层心跳发现）。房主断开 = 房间解散。
 */
class TcpLink internal constructor(
    /** 心跳间隔；默认 10 秒。测试里可以调小。 */
    private val beatIntervalMs: Long = DEFAULT_BEAT_INTERVAL_MS,
    /** 多久没收到任何帧就认为对方没了；默认 25 秒。 */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** 链路的对外状态；UI 想显示"几个人在"就收它。 */
    data class LinkState(
        /** 本机是不是房主（在监听端口）。 */
        val hosting: Boolean = false,
        /** 本机那条客户端连接是否活着（房主的是回环）。 */
        val connected: Boolean = false,
        /** 已报到的人，按报到顺序（房主自己在最前面）。 */
        val players: List<PlayerRef> = emptyList(),
        /** 一句给人看的话：开好了 / 断了 / 为什么断。 */
        val note: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** 频道表。注册/注销和派发共用这把锁，避免"解码用新频道、Handler 还没注册"的缝隙。 */
    private val handlers = LinkedHashMap<String, TcpHandler>()

    /** 服务端侧已报到的连接 → 人。房主自己也走回环，所以这里包含房主。 */
    private val players = LinkedHashMap<Peer, PlayerRef>()

    /** 所有连接（含本机那条客户端）最后收到帧的时间，心跳用。 */
    private val lastSeen = HashMap<Peer, Long>()

    private var server: TcpServer? = null
    private var heartbeat: Job? = null

    /** 本机那条客户端连接（房主是回环、客人是真网络）。 */
    @Volatile
    private var me: Peer? = null

    /** 本机身份：报到时由房主分配（空串 = 还没分到）。 */
    @Volatile
    private var localId: String = ""

    /** 房主自己那份"我是房主"的凭据：只在内存里，谁也不知道，所以谁也冒充不了房主。 */
    @Volatile
    private var hostClaim: String? = null

    /** 服务端给报到的人排号：`p1` / `p2` / …（会话内唯一就够了）。 */
    private var nextPlayerNumber = 1

    /** 报到完成（拿到身份）时唤醒 [host] / [join]。 */
    private val welcomeLatch = CountDownLatch(1)

    @Volatile
    private var closed = false

    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** 房主分给本机的身份；空串 = 还没报到完（[host] / [join] 返回时一定有值）。 */
    val playerId: String get() = localId

    /** 本机身份；房主标记由链路自己判定，客户端自称没用。 */
    val localPlayer: PlayerRef get() = PlayerRef(localId, server != null && !closed)

    // ── 开链路 / 关链路 ────────────────────────────────────────────────────

    /**
     * 当房主：监听端口（0 = 系统分配），然后像普通客人一样走 `127.0.0.1` 连自己。
     *
     * **阻塞**。返回时本机已经拿到身份（[playerId] 有值）；返回的端口是给上层做 NSD 广播用的
     * （链路不碰 Android）。
     */
    internal fun host(port: Int = 0): Int {        check(!closed) { "链路已经关了" }
        val claim = UUID.randomUUID().toString()
        val started = synchronized(lock) {
            check(server == null) { "已经在当房主了" }
            hostClaim = claim
            TcpServer(port, serverListener).start().also { server = it }
        }
        // 房主自己也是客户端：和客人同一条代码路径（区别只有 remoteHost 是回环 + 带上凭据）
        val client = Tcp.connect(LOOPBACK, started.localPort, clientListener)
        me = client
        // 先报到再干别的：同一把写锁 + 同一线程，保证 hello 排在后面所有帧前面
        sendLink(client, LinkFrame.Hello(hostClaim = claim))
        awaitIdentity()

        synchronized(lock) {
            startHeartbeatLocked()
            publishStateLocked(note = "房间开好了")
        }
        return started.localPort
    }

    /** 加入房主。**阻塞**；返回时 [playerId] 已经是房主分的身份。 */
    internal fun join(host: String, port: Int) {
        check(!closed) { "链路已经关了" }
        synchronized(lock) {
            check(server == null) { "房主不用 join" }
            check(me == null) { "已经连上了" }
        }
        val client = Tcp.connect(host, port, clientListener)
        me = client
        sendLink(client, LinkFrame.Hello())
        awaitIdentity()

        synchronized(lock) {
            startHeartbeatLocked()
            publishStateLocked(note = "已连上房主")
        }
    }

    /**
     * 关闭链路：先冲所有人道别（BYE），再断开、停服务端、取消心跳，最后通知各 Handler。
     *
     * 任意线程调用，重复调用安全。房主关链路 = 房间解散。
     */
    internal fun close(reason: String? = null) {
        closeInternal(reason)
    }

    /** 当前频道名列表（调试/测试用）。 */
    fun channels(): List<String> = synchronized(lock) { handlers.keys.toList() }

    // ── 频道注册 ────────────────────────────────────────────────────────────

    /**
     * 注册一个频道。
     *
     * @return `false` = 频道名被占用了（或者用了保留的 `_link`）
     */
    fun register(handler: TcpHandler): Boolean {
        synchronized(lock) {
            if (handler.ns == LINK_NS || handlers.containsKey(handler.ns)) return false
        }
        // attach 在进表之前：Handler 拿到 out 的时候，链路还不会把消息派给它
        handler.attach(ChannelOut(handler.ns))
        synchronized(lock) {
            if (handler.ns == LINK_NS || handlers.containsKey(handler.ns)) return false
            handlers[handler.ns] = handler
        }
        // 已经报到过了（游戏频道通常是中途注册的）：补一次身份
        localId.takeIf { it.isNotEmpty() }?.let { handler.onLinked(it) }
        return true
    }

    /** 注销频道（游戏结束、回大厅时用）。@return 是否真的移除了 */
    fun unregister(ns: String): Boolean = synchronized(lock) { handlers.remove(ns) != null }

    // ── 发送（本机 → 房主 / 本机 → 另一个玩家）────────────────────────────

    /** 用指定频道发一条给房主（客人方向）。房主自己发会走回环，也是同一条路。 */
    fun send(ns: String, payload: JsonElement?): Boolean {
        val peer = me ?: return false
        return peer.send(LinkCodec.encode(Frame(ns = ns, p = payload)))
    }

    /**
     * 经房主把一条帧转给另一个玩家（星型中转）。
     *
     * 房主侧直接投递；客人侧把帧发给自己那条连接、交给房主转发。`to` 不存在时**静默丢弃**
     * （中转是尽力而为，不会因为有人中途走了就让发送方崩）。
     *
     * @return 是否真的交给网络了（不代表对方一定收到）
     */
    fun sendTo(to: String, ns: String, payload: JsonElement?): Boolean = deliver(to, ns, payload)

    /**
     * 把某个玩家踢下线：先发 BYE（带原因），再关掉那条连接。
     *
     * 只有房主侧有意义；对方会从 `_link` 的 BYE 里拿到原因。
     */
    internal fun disconnect(playerId: String, reason: String): Boolean {
        val peer = synchronized(lock) {
            players.entries.firstOrNull { it.value.id == playerId }?.key
        } ?: return false
        sendLink(peer, LinkFrame.Bye(reason))
        peer.close()
        return true
    }

    // ── 派发 ────────────────────────────────────────────────────────────────

    /** 服务端侧：这是某个人发来的**命令**。 */
    private val serverListener = object : PeerListener {

        override fun onOpen(peer: Peer) {
            touch(peer)
        }

        override fun onLine(peer: Peer, line: String) {
            val frame = LinkCodec.decode(line) ?: return
            touch(peer)

            if (frame.v != LINK_VERSION) {
                reject(peer, "版本不一致（对端 ${frame.v}，本机 $LINK_VERSION）")
                return
            }
            if (frame.ns == LINK_NS) {
                onLinkFrame(peer, frame.p)
                return
            }

            val player = synchronized(lock) { players[peer] } ?: run {
                reject(peer, "先报到再说话")
                return
            }
            if (frame.to != null) {
                relayFrom(player, frame)
                return
            }
            handler(frame.ns)?.onCommand(player, frame.p)
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            onPeerClosed(peer, reason)
        }
    }

    /** 客户端侧：这是房主推来的**事件**（房主自己的回环客户端也走这里）。 */
    private val clientListener = object : PeerListener {

        override fun onOpen(peer: Peer) {
            touch(peer)
            // 报到（hello）由 host()/join() 在返回前就写出去，见那边的注释
        }

        override fun onLine(peer: Peer, line: String) {
            val frame = LinkCodec.decode(line) ?: return
            touch(peer)

            if (frame.v != LINK_VERSION) {
                closeInternal("版本不一致（房主 ${frame.v}，本机 $LINK_VERSION）")
                return
            }
            if (frame.ns == LINK_NS) {
                onLinkFrame(peer, frame.p)
                return
            }
            // from 只信房主填的（客户端发来的 from 在服务端就已经被丢掉了）
            handler(frame.ns)?.onEvent(frame.p, frame.from)
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            synchronized(lock) { lastSeen.remove(peer) }
            closeInternal(reason ?: "和房主的连接断了（$cause）")
        }
    }

    private fun handler(ns: String): TcpHandler? = synchronized(lock) { handlers[ns] }

    // ── `_link` 频道 ────────────────────────────────────────────────────────

    private fun onLinkFrame(peer: Peer, payload: JsonElement?) {
        when (val msg = LinkCodec.decodeLinkMessage(payload) ?: return) {
            is LinkFrame.Hello -> onHello(peer, msg)
            is LinkFrame.Welcome -> onLocalIdentity(msg.playerId)
            LinkFrame.Ping -> sendLink(peer, LinkFrame.Pong)
            LinkFrame.Pong -> Unit // touch() 已经记过了
            is LinkFrame.Bye -> if (peer === me) closeInternal(msg.reason) else reject(peer, msg.reason)
        }
    }

    /**
     * 有人报到：**在这里分配身份**。
     *
     * 只有房主（`server != null`）会走到分配那半边；客人收到 hello 说明对端搞错了，忽略。
     * 房主标记只可能出现在"回环 + 带着本机那份凭据"这条连接上——凭据没上过别人的手。
     */
    private fun onHello(peer: Peer, hello: LinkFrame.Hello) {
        if (server == null) return

        val assigned = synchronized(lock) {
            if (players.containsKey(peer)) return // 重复报到：忽略
            val claim = hello.hostClaim
            val isHost = claim != null && claim == hostClaim && peer.remoteHost == LOOPBACK
            PlayerRef("p${nextPlayerNumber++}", isHost).also { player ->
                players[peer] = player
                lastSeen[peer] = now()
                publishStateLocked()
            }
        }

        // 先告诉对方身份，再通知频道：频道拿到回调时已经能 sendTo 自己了
        sendLink(peer, LinkFrame.Welcome(assigned.id))
        handlersSnapshot().forEach { it.onPeerJoined(assigned) }
    }

    /** 本机拿到了身份：记下来、放行 [host] / [join]、通知所有频道。 */
    private fun onLocalIdentity(id: String) {
        localId = id
        welcomeLatch.countDown()
        handlersSnapshot().forEach { handler -> runCatching { handler.onLinked(id) } }
    }

    /** 等房主分身份。拿不到就把链路收掉再抛（[host] / [join] 的调用方负责变成"人类可读的失败"）。 */
    private fun awaitIdentity() {
        welcomeLatch.await(IDENTITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (localId.isNotEmpty()) return

        closeInternal("报到失败：没拿到身份")
        throw IOException("没拿到身份（房主没回应，或者连接被关了）")
    }

    // ── 发送实现（锁外写 socket）────────────────────────────────────────────

    private inner class ChannelOut(private val ns: String) : LinkOut {
        override fun sendTo(playerId: String, payload: JsonElement?): Boolean =
            deliver(playerId, ns, payload)

        override fun broadcast(payload: JsonElement?): Int = this@TcpLink.broadcast(ns, payload)

        override fun broadcastEach(build: (playerId: String) -> JsonElement?): Int =
            this@TcpLink.broadcastEach(ns, build)

        override fun sendCommand(payload: JsonElement?): Boolean = send(ns, payload)

        override fun disconnect(playerId: String, reason: String): Boolean =
            this@TcpLink.disconnect(playerId, reason)
    }

    /**
     * 投递一条给某个玩家的帧：本机这边有服务端（房主）就直接写那条连接；
     * 否则（客人）就交给自己那条连接、让房主转发。
     *
     * 两条路都会盖上 [Frame.from]（谁发的）——对方靠它知道这条是"直接给我的"，
     * 而不是房主广播出来的（广播的 `from` 是 null）。
     */
    private fun deliver(playerId: String, ns: String, payload: JsonElement?): Boolean {
        val peer = synchronized(lock) {
            players.entries.firstOrNull { it.value.id == playerId }?.key
        }
        if (peer != null) {
            return peer.send(LinkCodec.encode(Frame(ns = ns, from = localId, p = payload)))
        }
        val client = me ?: return false
        return client.send(LinkCodec.encode(Frame(ns = ns, to = playerId, p = payload)))
    }

    /** 房主侧：把一条带 `to` 的帧转给目标，并盖上真实的发送者。 */
    private fun relayFrom(sender: PlayerRef, frame: Frame) {
        val target = frame.to ?: return
        if (target == sender.id) return // 发给自己：没有意义
        val peer = synchronized(lock) {
            players.entries.firstOrNull { it.value.id == target }?.key
        } ?: return // 目标不在了：尽力而为，静默丢弃
        peer.send(
            LinkCodec.encode(
                Frame(v = LINK_VERSION, ns = frame.ns, from = sender.id, p = frame.p),
            ),
        )
    }

    private fun broadcast(ns: String, payload: JsonElement?): Int {
        val peers = synchronized(lock) { players.keys.toList() }
        if (peers.isEmpty()) return 0
        val line = LinkCodec.encode(Frame(ns = ns, p = payload))
        return peers.count { it.send(line) }
    }

    private fun broadcastEach(ns: String, build: (playerId: String) -> JsonElement?): Int {
        val targets = synchronized(lock) { players.entries.map { it.key to it.value.id } }
        var sent = 0
        targets.forEach { (peer, id) ->
            val payload = build(id) ?: return@forEach
            if (peer.send(LinkCodec.encode(Frame(ns = ns, p = payload)))) sent++
        }
        return sent
    }

    private fun sendLink(peer: Peer, msg: LinkFrame) {
        peer.send(LinkCodec.encode(Frame(ns = LINK_NS, p = LinkCodec.encode(msg))))
    }

    // ── 心跳 / 超时 ─────────────────────────────────────────────────────────

    private fun startHeartbeatLocked() {
        if (heartbeat != null) return
        heartbeat = scope.launch {
            while (isActive && !closed) {
                delay(beatIntervalMs)
                if (closed) return@launch

                val alive = ArrayList<Peer>()
                val dead = ArrayList<Peer>()
                val deadline = now() - timeoutMs
                synchronized(lock) {
                    lastSeen.forEach { (peer, seenAt) ->
                        if (seenAt < deadline) dead.add(peer) else alive.add(peer)
                    }
                }
                alive.forEach { sendLink(it, LinkFrame.Ping) }
                dead.forEach { peer ->
                    if (peer === me) closeInternal("和房主的连接超时") else reject(peer, "心跳超时")
                }
            }
        }
    }

    private fun touch(peer: Peer) {
        synchronized(lock) { lastSeen[peer] = now() }
    }

    // ── 断开 / 状态 ─────────────────────────────────────────────────────────

    private fun onPeerClosed(peer: Peer, reason: String?) {
        val left = synchronized(lock) {
            val had = players.remove(peer)
            lastSeen.remove(peer)
            publishStateLocked()
            had
        }
        if (left != null) handlersSnapshot().forEach { it.onPeerLeft(left.id) }
    }

    /** 给对方一句原因再断开（在锁外调用）。 */
    private fun reject(peer: Peer, reason: String) {
        sendLink(peer, LinkFrame.Bye(reason))
        peer.close()
    }

    private fun closeInternal(reason: String?) {
        if (closed) return
        closed = true

        val why = reason ?: "房间解散"
        val targets = synchronized(lock) { players.keys.toList() }
        targets.forEach { sendLink(it, LinkFrame.Bye(why)) }
        me?.let { sendLink(it, LinkFrame.Bye(why)) }

        runCatching { me?.close() }
        me = null
        runCatching { server?.stop() }
        server = null

        synchronized(lock) {
            players.clear()
            lastSeen.clear()
        }
        heartbeat?.cancel()
        heartbeat = null
        hostClaim = null

        val all = handlersSnapshot()
        synchronized(lock) { handlers.clear() }
        scope.cancel()
        welcomeLatch.countDown() // 正在等身份的人别干等到超时

        _state.value = LinkState(hosting = false, connected = false, players = emptyList(), note = why)
        all.forEach { handler -> runCatching { handler.onLinkClosed(why) } }
    }

    private fun handlersSnapshot(): List<TcpHandler> = synchronized(lock) { handlers.values.toList() }

    private fun publishStateLocked(note: String? = _state.value.note) {
        _state.value = LinkState(
            hosting = server != null && !closed,
            connected = me?.isOpen == true && !closed,
            players = players.values.toList(),
            note = note,
        )
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {

        private const val LOOPBACK = "127.0.0.1"

        /** 心跳：每 10 秒 ping 一次。 */
        const val DEFAULT_BEAT_INTERVAL_MS: Long = 10_000L

        /** 超过 25 秒没收到任何帧就认为对方没了。 */
        const val DEFAULT_TIMEOUT_MS: Long = 25_000L

        /** 报到后等房主分身份最多等多久。 */
        private const val IDENTITY_TIMEOUT_MS = 5_000L
    }
}
