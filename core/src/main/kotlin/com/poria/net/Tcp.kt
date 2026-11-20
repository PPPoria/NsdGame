package com.poria.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 客户端那一半：连上去，拿到一条 [Peer]。
 *
 * ```
 * val host = Tcp.connect("192.168.1.7", 45321, guestSession)   // 远端客人
 * val me   = Tcp.connect("127.0.0.1", server.localPort, hostPlayer) // 房主自己（走回环）
 * ```
 *
 * 房主自己也用这一个函数连自己的 [TcpServer]，拿到的是同一个 [Peer] 类型——
 * 这样会话/UI 只有一份，不用给房主开特例。代价是每方向几 KB 内核缓冲和几个系统调用，
 * 换来的是**只有一条链路要维护**。
 *
 * 没有 `TcpClient` 这样的对象：连接建立之后需要的一切（[Peer.send] / [Peer.close] /
 * [Peer.isOpen]）都在 [Peer] 上，再包一层只是多一个字段。
 */
object Tcp {

    /** 默认连接超时。局域网里超过 5 秒就是找不到了，硬等只会卡住调用方。 */
    const val DEFAULT_TIMEOUT_MS = 5_000

    /**
     * 建立连接并开始收消息。**阻塞**，成功才返回；失败一律抛 [IOException]
     * （拒绝连接、超时、域名解析失败都是它）。别在主线程调。
     *
     * @throws IOException 连不上
     */
    fun connect(
        host: String,
        port: Int,
        listener: PeerListener,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Peer {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            // 出牌这种小包，别让 Nagle 攒够 40ms 才发
            socket.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }

        val peer = Peer(
            id = 0,
            remoteHost = host,
            remotePort = port,
            socket = socket,
            onOpen = listener::onOpen,
            onLine = listener::onLine,
            onClosed = { p, cause, reason -> listener.onClosed(p, cause, reason) },
        )
        NetThreads.execute(peer::pump)
        return peer
    }
}
