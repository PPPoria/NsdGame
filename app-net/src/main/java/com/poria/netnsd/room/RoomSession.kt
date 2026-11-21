package com.poria.netnsd.room

import android.content.Context
import android.content.Intent
import com.poria.net.CloseCause
import com.poria.net.Peer
import com.poria.net.PeerListener
import com.poria.net.Tcp
import com.poria.net.TcpServer
import com.poria.netnsd.FoundHost
import com.poria.netnsd.ServiceAdvertiser
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

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
 * ## 图片（头像 / 自定义纹理）怎么走
 *
 * 同一条连接上分块，**点对点、按需**：
 * ```
 * 上传（设置自己的头像）：uploadAvatar(bytes) → 分块 CMD|{"type":"blobPut",…}
 *                          → 房主收齐验 hash → room.setAvatar → 广播新快照（快照里只有 id）
 * 下载（要别人的图）    ：requestBlob(id) → CMD|{"type":"blobRequest",…}
 *                          → 房主 BlobSender 一块一块 BLOB|{…} 发回来 → 拼进 BlobStore
 * ```
 * 快照里只放 id 不放字节，所以 50 张图不会乘上房间人数；一块 3KB，单次写只几毫秒。
 *
 * 线程：构造即返回，建房间/连接都在内部协程里（[Dispatchers.IO]），失败写进 `state.note`。
 * 唯一必须在主线程调用的是 [close]（[ServiceAdvertiser] 要 Looper）。
 */
class RoomSession private constructor(
    context: Context,
    private val playerName: String,
    private val isHost: Boolean,
    /** 进房时自带的头像（已经缩到 ≤512px 的 WebP）。第一次收到快照就传上去。 */
    private val initialAvatar: ByteArray? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 本机身份。客户端自己生成，服务端认可后就是这间房里的 playerId。 */
    private val myId: String = UUID.randomUUID().toString().take(RoomProtocol.MAX_ID)

    private val _state = MutableStateFlow(RoomState(host = isHost, myId = myId))
    val state: StateFlow<RoomState> = _state.asStateFlow()

    /** 自带的头像只传一次（每收到一次快照传一次就是洪水了）。 */
    private var avatarSent = false

    /** 房主明确说"没有这张图"的 id：UI 每次重画都调 requestBlob，这里兜一下别再问。 */
    private val brokenBlobs = ConcurrentHashMap.newKeySet<String>()

    /**
     * 图片仓库。房主用 `filesDir`（它是权威副本），客人用 `cacheDir`（删了还能再拉）。
     *
     * 房主会话里它同时是服务端仓库和客户端缓存——回环拉自己的图时两边是同一份，天然去重。
     */
    private val blobs = BlobStore(
        File(if (isHost) context.applicationContext.filesDir else context.applicationContext.cacheDir, "blobs")
    )

    /** 已经被拒绝过的上传 id：客户端看到 BLOB_ERR 就停下来，别把剩下几十块继续怼过去。 */
    private val failedUploads = ConcurrentHashMap.newKeySet<String>()

    /**
     * 房主侧：正在发给某个 peer 的图，key = `"<peerId>:<blobId>"`。
     *
     * **同一张图对同一条连接只允许一条流**：两条流交错会把块序打乱，`BlobStore` 按顺序拼，
     * 一旦乱序整份上传就失败（见 `BlobStoreTest` 的乱序用例）。Job 在这里的价值是能取消、
     * 能判断"还在发"——不需要发明 JobId，协议里真正的身份是 blobId。
     */
    private val streams = ConcurrentHashMap<String, Job>()

    /**
     * 客户端侧：已经在等/在拼的图 id → **超时器**。
     *
     * 作用有二：1) UI 每次重画都调 [requestBlob] 时去重，不然会变成请求风暴；
     * 2) 房主那边一直没回（掉线边缘、被配额挤掉）时，超时把占位撤掉，下次还能再要。
     */
    private val pendingBlobs = ConcurrentHashMap<String, Job>()

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

    // ── 图片 ────────────────────────────────────────────────────────────────

    /** 缓存里可用的图片文件（已经完整）。没有就返回 null，先画占位再 [requestBlob]。 */
    fun blobFile(id: String): File? = blobs.file(id)

    /**
     * 向房主要一张图；分块会从 [state] 的 `availableBlobs` 里冒出来。
     *
     * **可以随便调**：同一张图还在路上时再调一次只是空转（[pendingBlobs] 去重），
     * 所以 UI 在每次重画时直接调它就行，不用自己记"这张是不是已经要过了"。
     */
    fun requestBlob(id: String) {
        if (id.isBlank() || BlobStore.isBuiltin(id) || blobs.contains(id) || id in brokenBlobs) return

        val timeout = scope.launch(start = CoroutineStart.LAZY) {
            delay(BLOB_TIMEOUT_MS)
            if (pendingBlobs.remove(id) != null) {
                _state.update { it.copy(note = "那张图没等到，稍后再试") }
            }
        }
        if (pendingBlobs.putIfAbsent(id, timeout) != null) {
            timeout.cancel() // 已经在等了，别叠一条
            return
        }

        if (me?.send(RoomProtocol.requestBlob(id)) != true) {
            // 还没连上 / 连接已经断了：把占位撤掉，连上之后 UI 再要一次就行
            pendingBlobs.remove(id)
            timeout.cancel()
            return
        }
        // 图可能在发完这一步之前就已经拼完了（回环就几毫秒），那种情况别再把超时器点起来
        if (pendingBlobs[id] === timeout) timeout.start()
    }

    /** 这张图已经有了（下完了/上传完自己存了）：把超时器撤掉。 */
    private fun settleBlob(id: String) {
        pendingBlobs.remove(id)?.cancel()
    }

    /** 房主侧：把一份图发给一个 peer，同一 (连接, 图) 只留一条流。 */
    private fun serveBlob(peer: Peer, id: String) {
        val key = "${peer.id}:$id"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                BlobSender.send(peer, blobs, id)
            } finally {
                streams.remove(key)
            }
        }
        if (streams.putIfAbsent(key, job) != null) {
            job.cancel()
            return // 已经在发了：再起一条会打乱块序
        }

        if (streams.keys.count { it.startsWith("${peer.id}:") } > MAX_STREAMS_PER_PEER) {
            streams.remove(key)
            job.cancel()
            peer.send(RoomProtocol.blobError(id, "同时拉太多图了，稍后再试"))
            return
        }
        job.start()
    }

    /**
     * 设置自己的头像：先把图分块传给房主，房主收齐验过 hash 才把 id 写进快照。
     *
     * 调用前请把图缩到 ≤512px、压成 WebP/JPEG（512px 一般 20–60KB，也就是 10–20 块）。
     * **立刻返回**，真正的发送在内部 IO 协程里；本机先存一份，头像不用等房主回环就能画。
     */
    fun uploadAvatar(bytes: ByteArray) {
        scope.launch { upload(me, bytes) }
    }

    /** 第一次收到快照 = JOIN 被认可了：把带进来的头像传上去（只传一次）。 */
    private fun uploadInitialAvatar(peer: Peer) {
        val bytes = initialAvatar ?: return
        if (avatarSent) return
        avatarSent = true
        scope.launch { upload(peer, bytes) }
    }

    /**
     * 真正的上传。**用回调里拿到的 [peer]，别用 [me]**：回环很快，快照可能在
     * `Tcp.connect` 返回之前就到了，那时候 [me] 还是 null。
     */
    private suspend fun upload(target: Peer?, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > RoomProtocol.MAX_BLOB_BYTES) return
        val id = BlobStore.sha1Hex(bytes)
        val total = (bytes.size + RoomProtocol.BLOB_CHUNK_BYTES - 1) / RoomProtocol.BLOB_CHUNK_BYTES

        // 自己先有一份：换完头像立刻能画出来
        if (blobs.install(id, bytes)) {
            _state.update { it.copy(availableBlobs = it.availableBlobs + id) }
        }

        val peer = target ?: return
        failedUploads.remove(id)
        var index = 0
        while (index < total) {
            if (!peer.isOpen || id in failedUploads) return
            val from = index * RoomProtocol.BLOB_CHUNK_BYTES
            val to = minOf(from + RoomProtocol.BLOB_CHUNK_BYTES, bytes.size)
            val chunk = bytes.copyOfRange(from, to)
            if (!peer.send(RoomProtocol.putBlob(id, index, total, chunk))) return
            index++
        }
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
        // 正在发的图/等的图一起收掉：scope.cancel() 会停掉它们，这里顺手清掉引用
        streams.clear()
        pendingBlobs.values.forEach { it.cancel() }
        pendingBlobs.clear()
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

    /**
     * 服务端侧：认人（JOIN）→ 图片走单独的两条分支 → 其余命令交给 [Room]。
     *
     * 图片不经过 [Room]：房间只存 id，字节流是会话层的事（它拿着 [BlobStore]）。
     */
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
            when (command) {
                is RoomCommand.RequestBlob -> serveBlob(peer, command.id)

                is RoomCommand.PutBlob -> onPutBlob(peer, playerId, command)

                else -> {
                    val rejection = dispatch(room, playerId, command)
                    if (rejection != null) peer.send(RoomProtocol.error(rejection))
                }
            }
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            val playerId = synchronized(playerLock) { players.remove(peer) } ?: return
            room?.leave(playerId)
        }
    }

    /** 收一块上传。收齐且 hash 对得上，才算"这个玩家的头像换好了"。 */
    private fun onPutBlob(peer: Peer, playerId: String, command: RoomCommand.PutBlob) {
        if (command.role != RoomProtocol.ROLE_AVATAR) {
            peer.send(RoomProtocol.blobError(command.id, "不认识这种图片用途"))
            return
        }

        val done = blobs.put(command.id, command.index, command.total, command.data)
        if (!done) {
            // 只在第一块出错时回一句：后面那几十块客户端的循环会自己停
            if (command.index == 0) peer.send(RoomProtocol.blobError(command.id, "图片上传失败"))
            return
        }

        val rejection = room?.setAvatar(playerId, command.id)
        if (rejection != null) peer.send(RoomProtocol.error(rejection))
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

    /** 进房间进状态的那些命令。图片命令在上面单独处理了，这里只占位。 */
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
        is RoomCommand.RequestBlob -> null
        is RoomCommand.PutBlob -> null
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

    /** 客户端侧：房主和客人共用。连上就报到，之后只认服务端推来的快照/错误/请离开/图片块。 */
    private val clientListener = object : PeerListener {

        override fun onOpen(peer: Peer) {
            peer.send(RoomProtocol.join(myId, playerName))
        }

        override fun onLine(peer: Peer, line: String) {
            val event = RoomProtocol.decodeEvent(line) ?: return // 不认识的直接忽略，别炸
            when (event) {
                is RoomEvent.Snapshot -> {
                    _state.update {
                        it.copy(phase = SessionPhase.ONLINE, snapshot = event.snapshot, note = null)
                    }
                    // 报到被认可了：把带进来的头像传上去（只传这一次）
                    uploadInitialAvatar(peer)
                }

                is RoomEvent.Error -> _state.update { it.copy(note = event.reason) }

                is RoomEvent.Bye -> _state.update {
                    it.copy(phase = SessionPhase.CLOSED, note = event.reason)
                }

                is RoomEvent.Blob -> {
                    if (blobs.put(event.id, event.index, event.total, event.data)) {
                        settleBlob(event.id)
                        _state.update { it.copy(availableBlobs = it.availableBlobs + event.id) }
                    }
                }

                is RoomEvent.BlobError -> {
                    failedUploads += event.id
                    brokenBlobs += event.id
                    settleBlob(event.id)
                    _state.update { it.copy(note = event.reason) }
                }
            }
        }

        override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
            pendingBlobs.values.forEach { it.cancel() }
            pendingBlobs.clear()
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

        /** 同一条连接上最多同时发几张图。再多就回一句"稍后再试"，别让对端一次拉爆 FD。 */
        private const val MAX_STREAMS_PER_PEER = 20

        /** 等一张图最多等多久，超时就把占位撤掉（UI 下次重画会再要一次）。 */
        private const val BLOB_TIMEOUT_MS = 30_000L

        /** 开房。立刻返回，建好了会有 `state`。 */
        fun host(
            context: Context,
            roomName: String,
            playerName: String,
            avatar: ByteArray? = null,
        ): RoomSession =
            RoomSession(context, playerName, isHost = true, initialAvatar = avatar)
                .apply { startHost(context, roomName) }

        /** 加入别人搜到的房间。立刻返回，连上了会有 `state`。 */
        fun guest(
            context: Context,
            target: FoundHost,
            playerName: String,
            avatar: ByteArray? = null,
        ): RoomSession =
            RoomSession(context, playerName, isHost = false, initialAvatar = avatar)
                .apply { startGuest(target) }
    }
}
