package com.poria.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ProtocolException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条已经连上的连接。
 *
 * **一类连接，两种来源**：远端客人（[TcpServer] 收进来的 socket）和房主自己
 * （`Tcp.connect("127.0.0.1", localPort, …)`）都是这个类型、走同一条代码路径，
 * 所以会话/UI 里不该出现"我是房主所以走另一条路"的分支。
 *
 * 线程约定：`onOpen → onLine… → onClosed` 全部在同一条读线程上按序回调，[send] 可以从
 * 任意线程调（内部有写锁）。
 *
 * 不要自己 new：由 [TcpServer] / [Tcp.connect] 创建并起好读线程。
 */
class Peer internal constructor(
    /** 服务端内自增的连接号。客户端那条固定为 0，只用来打日志/做 map key。 */
    val id: Int,
    /** 对端 IP。房主自己那条是 `127.0.0.1`。 */
    val remoteHost: String,
    /** 对端端口。 */
    val remotePort: Int,
    private val socket: Socket,
    private val onOpen: (Peer) -> Unit,
    private val onLine: (Peer, String) -> Unit,
    private val onClosed: (Peer, CloseCause, String?) -> Unit,
) {

    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Any()
    private val finished = AtomicBoolean(false)

    /** 是不是"本机主动关的"。读线程醒来时靠它区分该报 LOCAL 还是 ERROR。 */
    @Volatile
    private var closeRequested = false

    @Volatile
    var isOpen: Boolean = true
        private set

    /**
     * 发一条消息（由 [Wire] 补上 `'\n'`）。
     *
     * @return `false` 表示没发出去（连接已断）；此时**不要重试**，
     *   `onClosed` 会（或已经）被回调一次，按它处理就行。
     * @throws IllegalArgumentException 消息含换行或超过 [Wire.MAX_LINE_BYTES]——本机代码 bug
     */
    fun send(line: String): Boolean {
        if (!isOpen) return false
        return try {
            synchronized(writeLock) { Wire.writeLine(output, line) }
            true
        } catch (e: IOException) {
            // 写挂了只关 socket，**不在这里回调 onClosed**：回调统一由读线程发，
            // 下游收到的 onOpen/onLine/onClosed 顺序才是可信的。
            shutdown()
            false
        }
    }

    /**
     * 主动断开。`onClosed(LOCAL)` 会由读线程异步回调一次。
     *
     * 重复调用安全。
     */
    fun close() {
        closeRequested = true
        shutdown()
    }

    /** 读线程体。由 [TcpServer] / [Tcp] 丢进 [NetThreads]，不要自己调。 */
    internal fun pump() {
        if (finished.get()) return
        try {
            onOpen(this)
            val input = BufferedInputStream(socket.getInputStream())
            while (true) {
                val line = Wire.readLine(input)
                if (line == null) {
                    // EOF：本机关的算 LOCAL，对端关的算 REMOTE
                    finish(if (closeRequested) CloseCause.LOCAL else CloseCause.REMOTE, null)
                    return
                }
                onLine(this, line)
            }
        } catch (e: ProtocolException) {
            finish(CloseCause.ERROR, e.message ?: "协议错误")
        } catch (e: IOException) {
            // close() 会把阻塞中的 read 撞成 SocketException，这不是错误
            finish(if (closeRequested) CloseCause.LOCAL else CloseCause.ERROR, e.message ?: e.toString())
        } catch (e: Throwable) {
            // 下游回调（onOpen / onLine）自己抛了：先把连接收干净，
            // 再把异常扔给线程的默认处理器打日志——别静默吞掉 bug
            finish(CloseCause.ERROR, "下游回调抛异常：$e")
            throw e
        } finally {
            // 兜底：不管走哪条路都不留半开的 socket
            shutdown()
        }
    }

    private fun shutdown() {
        isOpen = false
        runCatching { socket.close() }
    }

    private fun finish(cause: CloseCause, reason: String?) {
        // 只回调一次：close()、读线程、send 失败三条路都往这里汇
        if (!finished.compareAndSet(false, true)) return
        shutdown()
        onClosed(this, cause, reason)
    }
}
