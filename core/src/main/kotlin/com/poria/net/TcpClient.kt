package com.poria.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 连到房主的一条连接。
 *
 * ```
 * val client = TcpClient(host, port, listener)
 * client.connect()        // 阻塞：别在主线程调
 * client.send("hello")
 * client.close()
 * ```
 *
 * 线程：[connect] 内部起一条读线程，之后 onOpen / onLine / onClosed 都在它上面按序回调
 * （见 [Peer]）；[send] 可以从任意线程调。
 */
class TcpClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) {

    /** 客户端事件。都有空实现，覆盖需要的就行。回调里别抛异常（理由见 [TcpServer.Listener]）。 */
    interface Listener {
        /** 连上了。可能在 [connect] 返回之前就被回调。 */
        fun onOpen(client: TcpClient) {}

        /** 收到一条消息。 */
        fun onLine(client: TcpClient, line: String) {}

        /** 断开，只回调一次。 */
        fun onClosed(client: TcpClient, cause: CloseCause, reason: String?) {}
    }

    @Volatile
    private var peer: Peer? = null

    /** 连上且还没断，才是 true。 */
    val isOpen: Boolean get() = peer?.isOpen == true

    /**
     * 建立连接并开始收消息。**阻塞**，成功才返回；失败一律抛 [IOException]
     * （拒绝连接、超时、域名解析失败都是它），调用方接一下就行。
     */
    fun connect(timeoutMs: Int = DEFAULT_TIMEOUT_MS) {
        check(peer == null) { "已经 connect 过了，要重连就新建一个 TcpClient" }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            // 出牌这种小包，别让 Nagle 攒够 40ms 才发
            socket.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }

        val created = Peer(
            id = 0,
            remoteHost = host,
            remotePort = port,
            socket = socket,
            onOpen = { listener.onOpen(this) },
            onLine = { _, line -> listener.onLine(this, line) },
            onClosed = { _, cause, reason -> listener.onClosed(this, cause, reason) },
        )
        peer = created
        NetThreads.execute(created::pump)
    }

    /** 发一条消息，返回 false 表示连接已断（别再重试了，看 onClosed）。 */
    fun send(line: String): Boolean = peer?.send(line) ?: false

    /** 主动断开。`onClosed(LOCAL)` 异步回调一次。重复调用安全。 */
    fun close() {
        peer?.close()
    }

    companion object {
        /** 默认连接超时。局域网里超过 5 秒就是找不到了，硬等只会卡住 UI。 */
        const val DEFAULT_TIMEOUT_MS = 5_000
    }
}
