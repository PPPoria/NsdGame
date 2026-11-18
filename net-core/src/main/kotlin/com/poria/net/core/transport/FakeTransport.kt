package com.poria.net.core.transport

import com.poria.net.proto.frame.FrameHeader
import com.poria.net.proto.frame.FrameProtocolException
import com.poria.net.proto.frame.Message
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 纯内存传输实现：不碰 socket、不占端口、毫秒级。
 *
 * 归属：传输与消息协议。消费者：会话层单测（M1 "`FakeTransport` 驱动全流程不碰 socket"）。
 *
 * 使用方式与 [TcpTransport] 完全一致，区别只有一点：`Endpoint.Tcp` 的 `host` 不参与寻址，
 * 只用 `port` 找到对应的监听器（`Endpoint` 是 FROZEN 的 sealed interface，不允许多加一个
 * `Endpoint.Fake`）。端口传 0 时由本类从 [DEFAULT_FIRST_PORT] 起分配。
 *
 * **背压语义与真实传输对齐**（容易踩的坑）：单连接入站缓冲同样是 64 条，`send` 是**挂起**的
 * （不是 `trySend` 满了就抛）。会话层测试里"连续发 100 条、对端迟点再收"的行为在 Fake 与
 * 真机上一致，不会出现"Fake 全绿、真机背压时行为不同"。
 *
 * 不做的事：不模拟延迟、不模拟丢包、不模拟半包 —— 它验证的是**会话层逻辑**，
 * 帧层的半包/粘包由 `:net-proto` 的暴力切分测试负责。
 */
class FakeTransport : Transport {

    override val scheme: String = "fake"

    private val nextPort = AtomicInteger(DEFAULT_FIRST_PORT)
    private val listeners = ConcurrentHashMap<Int, FakeListener>()

    override suspend fun listen(port: Int): Listener {
        val actual = if (port == 0) allocatePort() else port
        val listener = FakeListener(actual)
        if (listeners.putIfAbsent(actual, listener) != null) {
            throw IOException("端口已被占用：$actual")
        }
        return listener
    }

    override suspend fun connect(endpoint: Endpoint, timeoutMs: Long): Connection {
        val tcp = when (endpoint) {
            is Endpoint.Tcp -> endpoint
        }
        val listener = listeners[tcp.port] ?: throw IOException("没有监听器：port=${tcp.port}")
        return listener.accept(tcp.host)
    }

    /** 关闭全部监听器与连接（`LocalClose`）。 */
    fun close() {
        listeners.values.forEach { runCatching { it.closeNow() } }
        listeners.clear()
    }

    private fun allocatePort(): Int {
        while (true) {
            val port = nextPort.getAndUpdate { if (it >= MAX_PORT) DEFAULT_FIRST_PORT else it + 1 }
            if (!listeners.containsKey(port)) return port
        }
    }

    private inner class FakeListener(override val localPort: Int) : Listener {

        private val accepted = Channel<Connection>(capacity = 32)
        private val live = CopyOnWriteArrayList<FakeConnection>()

        override val incoming: Flow<Connection> = flow { for (conn in accepted) emit(conn) }

        suspend fun accept(label: String): Connection {
            val (dialer, listenerSide) = FakeConnection.pair(
                dialerLabel = "fake->$label:$localPort",
                listenerLabel = "fake<-$label:$localPort",
            )
            live += dialer
            live += listenerSide
            accepted.send(listenerSide)
            return dialer
        }

        override suspend fun close() {
            closeNow()
        }

        fun closeNow() {
            accepted.close()
            live.toList().forEach { it.shutdown(CloseReason.LocalClose) }
            listeners.remove(localPort)
        }
    }

    private class FakeConnection(override val peerLabel: String) : Connection {

        private lateinit var peer: FakeConnection
        private val inbox = Channel<Message>(capacity = 64)
        private val closeReason = CompletableDeferred<CloseReason>()

        /** 关闭原因快照（避免用实验性的 `getCompleted()`）。 */
        @Volatile
        private var completedReason: CloseReason? = null

        override val incoming: Flow<Message> = flow { for (msg in inbox) emit(msg) }

        override val closed: Flow<CloseReason> = flow { emit(closeReason.await()) }

        override suspend fun send(msg: Message) {
            completedReason?.let { throw IOException("连接已关闭（$peerLabel）：$it") }
            // 与真实传输保持同一套帧上限校验，免得测试里造出 TCP 永远发不出去的消息
            if (msg.size > FrameHeader.SIZE + FrameHeader.MAX_PAYLOAD_BYTES) {
                throw FrameProtocolException(
                    FrameProtocolException.Kind.PAYLOAD_TOO_LARGE,
                    "消息 ${msg.size} 字节超出帧上限",
                )
            }
            // ⚠️ 必须是**挂起**的 send（不是 trySend）：真实传输在背压时会挂起，
            // 会话层若在测试里依赖"send 会等"，Fake 与真机行为必须一致，否则假绿。
            try {
                peer.inbox.send(msg)
            } catch (e: ClosedSendChannelException) {
                throw IOException("对端已关闭（$peerLabel）", e)
            }
        }

        override suspend fun close(reason: CloseReason) {
            shutdown(reason)
        }

        fun shutdown(reason: CloseReason) {
            if (closeReason.isCompleted) return
            closeReason.complete(reason)
            completedReason = reason
            inbox.close()
            // 对端感受到的是"远端关了"，与真实 socket 行为一致
            if (!peer.closeReason.isCompleted) {
                peer.shutdown(CloseReason.RemoteClosed)
            }
        }

        companion object {
            fun pair(dialerLabel: String, listenerLabel: String): Pair<FakeConnection, FakeConnection> {
                val dialer = FakeConnection(dialerLabel)
                val listener = FakeConnection(listenerLabel)
                dialer.peer = listener
                listener.peer = dialer
                return dialer to listener
            }
        }
    }

    companion object {
        /** 假端口起始值，远离真实端口与测试常用端口。 */
        const val DEFAULT_FIRST_PORT: Int = 40_000
        private const val MAX_PORT: Int = 41_000
    }
}
