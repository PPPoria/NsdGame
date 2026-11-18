package com.poria.net.core.transport

import com.poria.net.proto.frame.FrameHeader
import com.poria.net.proto.frame.FrameIo
import com.poria.net.proto.frame.FrameProtocolException
import com.poria.net.proto.frame.Message
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 阻塞 IO 的 TCP 传输实现（ADR-008：每连接两个协程 + 专属 dispatcher）。
 *
 * 归属：传输与消息协议。本类**不解析 JSON**：只切帧、校验帧头、搬字节（"只搬不解析"）。
 *
 * 必查项落实：
 * | 项 | 实现 |
 * |---|---|
 * | 端口 | [listen] 默认 0 → 系统分配，见 `ServerSocket.localPort` |
 * | 并发模型 | 每连接两个协程：读循环 + 写 Channel |
 * | 调度器 | 专属 **cached** 线程池，线程名 `nsdnet-N`；**不用 `:base` 的 ThreadPool**（其 AbortPolicy 会炸掉一次发送），也不蹭 `Dispatchers.IO` |
 * | 读超时 | `Socket.soTimeout`（默认 0 = 阻塞到有数据；心跳超时归会话层） |
 * | 写超时 | 每次 [Connection.send] 用 `withTimeoutOrNull` 兜住，超时即断开，防对端不消费导致挂死 |
 * | 帧长上限 | [FrameHeader.MAX_PAYLOAD_BYTES]，超限 `CloseReason.Protocol` 并断开 |
 *
 * 用法：进程内一个实例即可（`:app` 的 `LanRuntime` 持有）。用完调 [close]。
 *
 * **线程池容量（ADR-013，修订 ADR-008）**：每条连接的读循环会**长期占住一个线程**（阻塞 IO 的必然代价），
 * 所以"能撑多少连接"完全由连接数决定，而不是由线程池参数决定：
 * - 默认 `maxIoThreads = null` → **cached 池**（空闲线程 60s 回收、有活才建线程），线程数天然被
 *   会话层的 `maxPlayers = 6` 与现实连接数限住，**同一实例既 listen 又 connect 也不会抽干**；
 * - **真正的并发上界是会话层的 maxPlayers**，不是线程池参数——把上界放在线程池是对错误的层设限
 *   （ADR-008 把 `poolSize` 钉成常量 8 时的症状是：房主 6 人 + 写协程共用同一 dispatcher，
 *   峰值 7~11 线程即被抽干，表现为 `写超时 10000ms`，属于最难诊断的一类）；
 * - `maxIoThreads` 只作为**兜底旋钮**保留（例如恶意对端狂开连接时想给线程总数一个天花板）：
 *   传非 null 即切成 fixed 池。注意 fixed 池满了会让读协程排队 → 又是同一类写超时症状，
 *   所以正常部署不要设它。
 */
class TcpTransport(
    /**
     * 读超时（毫秒）。0 = 一直阻塞到对端发数据或关闭。
     * 心跳判定在会话层，本层只提供 `soTimeout` 这个旋钮。
     */
    private val readTimeoutMs: Int = 0,
    /** 单条消息写超时（毫秒）。超时即认为对端不消费，断开。 */
    private val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
    /**
     * IO 线程数上限。
     * - `null`（默认，ADR-013）：cached 池 —— 线程数由连接数决定，而连接数上界由会话层的
     *   `maxPlayers` 决定。同一实例既 listen 又 connect 也是安全的。
     * - 非 null：切成 fixed 池（兜底旋钮）。⚠️ **设了就必须 ≥ 预期连接数 + 4**，否则就是把
     *   ADR-008 那个坑原样装回来；[MIN_FIXED_IO_THREADS] 是硬下限，构造时 `require` 挡住。
     *   语义上这个旋钮本身就是"把 P0 装回来"——别为了"保险"去设它。
     */
    maxIoThreads: Int? = null,
) : Transport, AutoCloseable {

    init {
        require(maxIoThreads == null || maxIoThreads >= MIN_FIXED_IO_THREADS) {
            "maxIoThreads=$maxIoThreads 太低：fixed 池必须 ≥ $MIN_FIXED_IO_THREADS（见 ADR-013）。" +
                "而且它只是兜底旋钮——真正该做的不是设它，而是让线程数随连接数伸缩（保持 null）。"
        }
    }

    override val scheme: String = "tcp"

    private val threadSeq = AtomicInteger(0)

    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "nsdnet-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }

    /** 专属调度器（ADR-013）：默认 cached 池，线程数随连接数伸缩。 */
    private val dispatcher = (
        if (maxIoThreads == null) {
            Executors.newCachedThreadPool(threadFactory)
        } else {
            Executors.newFixedThreadPool(maxIoThreads, threadFactory)
        }
        ).asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override suspend fun listen(port: Int): Listener {
        val server = withContext(dispatcher) {
            ServerSocket().apply {
                reuseAddress = true
                // 绑 0.0.0.0（局域网可达）；port=0 由系统分配真实端口
                bind(InetSocketAddress(port))
            }
        }
        return TcpListener(server)
    }

    override suspend fun connect(endpoint: Endpoint, timeoutMs: Long): Connection {
        val tcp = when (endpoint) {
            is Endpoint.Tcp -> endpoint
        }
        val socket = withContext(dispatcher) {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(tcp.host, tcp.port), timeoutMs.toInt())
            }
        }
        return TcpConnection(socket, peerLabel = "${tcp.host}:${tcp.port}")
    }

    /** 关闭传输：取消所有协程并释放线程池。已建立的连接随之断开（`LocalClose`）。 */
    override fun close() {
        scope.cancel()
        dispatcher.close()
    }

    // ───────────────────────────── Listener ─────────────────────────────

    private inner class TcpListener(private val server: ServerSocket) : Listener {

        override val localPort: Int = server.localPort

        private val accepted = Channel<Connection>(capacity = ACCEPT_BACKLOG)
        private val live = CopyOnWriteArrayList<TcpConnection>()

        override val incoming: Flow<Connection> = flow {
            // 用 flow{} 而不是 consumeAsFlow()：收集方取消（例如 UI 只取第一个）不能关掉 Channel
            for (conn in accepted) emit(conn)
        }

        private val acceptJob: Job = scope.launch { acceptLoop() }

        private suspend fun acceptLoop() {
            while (currentCoroutineContext().isActive) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (server.isClosed) break
                    // 单个 accept 失败（EMFILE 等）不致命：短暂退避后继续，避免把监听打挂
                    delay(ACCEPT_RETRY_DELAY_MS)
                    continue
                }
                val label = "in:${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"
                val conn = TcpConnection(
                    socket = socket,
                    peerLabel = label,
                    onClosed = { closed -> live.remove(closed) },
                )
                live += conn
                try {
                    accepted.send(conn)
                } catch (e: ClosedSendChannelException) {
                    conn.close(CloseReason.LocalClose)
                    break
                }
            }
        }

        override suspend fun close() {
            closeQuietly(server)
            acceptJob.cancelAndJoin()
            accepted.close()
            live.toList().forEach { it.close(CloseReason.LocalClose) }
        }
    }

    // ───────────────────────────── Connection ─────────────────────────────

    private inner class TcpConnection(
        private val socket: Socket,
        override val peerLabel: String,
        private val onClosed: (TcpConnection) -> Unit = {},
    ) : Connection {

        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())

        private val outgoing = Channel<Outgoing>(capacity = OUTBOUND_BUFFER)
        private val incomingBytes = Channel<Message>(capacity = INBOUND_BUFFER)

        private val closeReason = CompletableDeferred<CloseReason>()
        private val shutdownLock = Any()

        /** 关闭原因的快照，避免用实验性的 `getCompleted()`。 */
        @Volatile
        private var completedReason: CloseReason? = null

        @Volatile
        private var shutdownStarted = false

        /** 所有在飞行 / 排队中的发送回执。关闭时统一释放，避免调用方干等到写超时。 */
        private val pendingAcks: MutableSet<CompletableDeferred<Unit>> = ConcurrentHashMap.newKeySet()

        override val incoming: Flow<Message> = flow {
            // 同 Listener：收集方取消不得影响连接本身
            for (msg in incomingBytes) emit(msg)
        }

        override val closed: Flow<CloseReason> = flow { emit(closeReason.await()) }

        private var readerJob: Job? = null
        private var writerJob: Job? = null

        init {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            readerJob = scope.launch { readLoop() }
            writerJob = scope.launch { writeLoop() }
        }

        override suspend fun send(msg: Message) {
            completedReason?.let { throw IOException("连接已关闭（$peerLabel）：$it") }

            val ack = CompletableDeferred<Unit>()
            pendingAcks += ack
            try {
                outgoing.send(Outgoing(msg, ack))
            } catch (e: ClosedSendChannelException) {
                pendingAcks -= ack
                throw IOException("连接已关闭（$peerLabel）：发送队列已关闭", e)
            }

            // ADR-008：withTimeoutOrNull 兜住写超时，防对端不消费导致挂死
            val written = withTimeoutOrNull(writeTimeoutMs) {
                ack.await()
                true
            }
            pendingAcks -= ack
            if (written == null) {
                shutdown(CloseReason.Io(IOException("写超时 ${writeTimeoutMs}ms（$peerLabel）")))
                throw IOException("写超时 ${writeTimeoutMs}ms（$peerLabel）：对端可能未消费")
            }
        }

        override suspend fun close(reason: CloseReason) {
            shutdown(reason)
        }

        private suspend fun writeLoop() {
            try {
                for (item in outgoing) {
                    output.write(item.bytes)
                    output.flush()
                    pendingAcks -= item.ack
                    item.ack.complete(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 让排队中的 send() 立即失败，而不是等到写超时
                outgoing.close(t)
                shutdown(if (t is IOException) CloseReason.Io(t) else CloseReason.Io(IOException(t)))
            }
        }

        private suspend fun readLoop() {
            try {
                while (true) {
                    // 一整帧（头 + 载荷）；半包在此处被吸收
                    val frame = FrameIo.readRawFrame(input)
                    incomingBytes.send(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EOFException) {
                // 帧边界上的正常结束：对端 socket 关了
                shutdown(CloseReason.Eof)
            } catch (e: FrameProtocolException) {
                shutdown(CloseReason.Protocol(e.message ?: e.kind.name))
            } catch (e: SocketTimeoutException) {
                // 只在显式配置 readTimeoutMs 时出现；视为 IO 异常交给会话层处理
                shutdown(CloseReason.Io(e))
            } catch (e: SocketException) {
                // RST / Stream closed / Broken pipe
                shutdown(CloseReason.RemoteClosed)
            } catch (t: Throwable) {
                shutdown(CloseReason.Io(if (t is IOException) t else IOException(t)))
            }
        }

        private fun shutdown(reason: CloseReason) {
            synchronized(shutdownLock) {
                if (shutdownStarted) return
                shutdownStarted = true
            }
            closeReason.complete(reason)
            completedReason = reason

            // 先把所有等待写回的 send() 释放掉，再关流。否则它们要等到写超时才醒。
            val pending = IOException("连接已关闭（$peerLabel）：$reason")
            pendingAcks.toList().forEach { it.completeExceptionally(pending) }
            pendingAcks.clear()

            incomingBytes.close()
            outgoing.close()
            // 先关 socket（解开阻塞中的 read/write），再取消协程
            closeQuietly(socket)
            readerJob?.cancel()
            writerJob?.cancel()
            onClosed(this)
        }
    }

    private class Outgoing(val bytes: ByteArray, val ack: CompletableDeferred<Unit>)

    companion object {
        /** fixed 池的硬下限：低于 8 条等于把 ADR-008 的 P0 装回来。 */
        const val MIN_FIXED_IO_THREADS: Int = 8

        /** 单条消息写超时。对端 10s 不消费就认为它挂了。 */
        const val DEFAULT_WRITE_TIMEOUT_MS: Long = 10_000

        /** 已 accept 但尚未被收集方取走的连接队列上限（背压，不是无限缓冲）。 */
        private const val ACCEPT_BACKLOG = 32

        /** 待发送消息队列上限。满了 `send` 会挂起（背压）。 */
        private const val OUTBOUND_BUFFER = 128

        /** 入站消息缓冲。满了读循环会挂起 → TCP 窗口收缩 → 对端被背压。 */
        private const val INBOUND_BUFFER = 64

        private const val ACCEPT_RETRY_DELAY_MS = 50L

        internal fun closeQuietly(closeable: AutoCloseable?) {
            try {
                closeable?.close()
            } catch (ignored: IOException) {
                // 关闭失败不影响状态转移
            }
        }
    }
}
