package com.poria.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网对局的"房主"：监听一个端口，把每条进来的连接变成一个 [Peer]。
 *
 * ```
 * val server = TcpServer(0, listener).start()   // 0 = 让系统挑一个空闲端口
 * advertise(server.localPort)                   // 把这个端口通过 NSD 广播出去（见 :app-net）
 * ...
 * server.stop()
 * ```
 *
 * 线程：[start] 之后固定 1 条 accept 线程 + 每条连接 1 条读线程；回调线程见 [Peer]。
 */
class TcpServer(
    /** 想监听的端口。默认 0 = 系统分配，真实端口看 [localPort]。 */
    private val port: Int = 0,
    private val listener: Listener,
) {

    /**
     * 服务端事件。所有方法都有空实现：下游只关心哪个就覆盖哪个。
     *
     * ⚠️ 回调里别抛异常、别做耗时活：它跑在那条连接的读线程上，抛出去会打断读线程
     * （Android 上未捕获异常还会带走整个进程）。连接本身会被收干净并补一次
     * [onPeerClosed]（`cause = ERROR`）。
     */
    interface Listener {
        /** 新连接已可用（可以立刻 [Peer.send]），发生在任何 [onPeerLine] 之前。 */
        fun onPeerOpen(peer: Peer) {}

        /** 收到一条消息。同一条连接的消息按发送顺序、在同一条线程上到达。 */
        fun onPeerLine(peer: Peer, line: String) {}

        /** 连接断开，每条连接只回调一次。 */
        fun onPeerClosed(peer: Peer, cause: CloseCause, reason: String?) {}

        /** accept 循环挂了（一般是本机网络异常），随后服务已停止。正常情况下不会触发。 */
        fun onServerDown(reason: String, error: Throwable?) {}
    }

    private val lock = Any()
    private val peers = LinkedHashMap<Int, Peer>()
    private val nextPeerId = AtomicInteger(1)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort = -1

    /** 真实监听的端口。构造时传 0 的话，就是系统分配的端口；没跑起来是 -1。 */
    val localPort: Int get() = boundPort

    val isRunning: Boolean get() = serverSocket != null

    /** 当前连接快照（顺序 = 连进来的顺序）。发通知时遍历它，别在下游自己存。 */
    fun peers(): List<Peer> = synchronized(lock) { peers.values.toList() }

    /**
     * 绑定端口并开始 accept。**同步**执行：端口被占用等错误直接抛 [IOException]，
     * 调用方在 [start] 返回后就能拿到可信的 [localPort]。
     *
     * @throws IOException 绑定失败（端口被占用、没有网络权限……）
     */
    fun start(): TcpServer {
        check(!isRunning) { "已经在跑了，先 stop()" }
        val socket = ServerSocket()
        try {
            // 进程刚重启时旧连接还在 TIME_WAIT，不复用地址会"端口被占用"
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), BACKLOG)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        serverSocket = socket
        boundPort = socket.localPort
        NetThreads.execute { acceptLoop(socket) }
        return this
    }

    /**
     * 给所有连接发同一条消息。
     *
     * @return 真正发出去的条数（断掉的不算）
     * @throws IllegalArgumentException 消息含换行或超长（本机代码 bug）
     */
    fun broadcast(line: String): Int {
        Wire.checkLine(line)
        return peers().count { it.send(line) }
    }

    /** 停止监听并断开所有连接。重复调用安全。 */
    fun stop() {
        val socket = synchronized(lock) {
            serverSocket.also { serverSocket = null }
        }
        boundPort = -1
        socket?.let { runCatching { it.close() } }
        // 每条连接的 onPeerClosed(LOCAL) 由各自的读线程回调
        peers().forEach { it.close() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val accepted = try {
                socket.accept()
            } catch (e: IOException) {
                if (socket.isClosed) return // stop() 的正常路径（SocketException 也走这里）
                // 到这里服务已经废了：回调再抛异常也只能吞掉，否则 stop() 都跑不到
                runCatching { listener.onServerDown("accept 失败", e) }
                stop()
                return
            }

            val peer = try {
                // 出牌这种小包，别让 Nagle 攒够 40ms 才发
                accepted.tcpNoDelay = true
                Peer(
                    id = nextPeerId.getAndIncrement(),
                    remoteHost = accepted.inetAddress?.hostAddress ?: "unknown",
                    remotePort = accepted.port,
                    socket = accepted,
                    onOpen = listener::onPeerOpen,
                    onLine = listener::onPeerLine,
                    onClosed = { p, cause, reason ->
                        synchronized(lock) { peers.remove(p.id) }
                        listener.onPeerClosed(p, cause, reason)
                    },
                )
            } catch (e: IOException) {
                // 连上了却拿不到输入输出流：丢掉这条，继续 accept
                runCatching { accepted.close() }
                continue
            }

            // 先入表再起读线程：保证 onPeerOpen 里 broadcast 时自己也已经在表里
            synchronized(lock) { peers[peer.id] = peer }
            NetThreads.execute(peer::pump)
        }
    }

    private companion object {
        /** accept 队列长度。一局最多 6 人 + 房主，8 足够。 */
        const val BACKLOG = 8
    }
}
