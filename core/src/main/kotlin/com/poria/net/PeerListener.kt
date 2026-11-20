package com.poria.net

/**
 * 一条连接的事件。**远端客人和房主自己（回环连上来的那条）用的是同一个接口**，
 * 所以会话代码只写一份，房主 UI 和客人 UI 不用分叉。
 *
 * 线程约定（下游只要记这一句）：同一个连接的 `onOpen → onLine… → onClosed`
 * 全部在它自己的读线程上按序回调，所以在回调里处理消息不用加锁；不同连接之间并发。
 *
 * ⚠️ 回调里别抛异常、别做长时间阻塞的发送：它跑在连接自己的读线程上，抛出去会打断读线程
 * （Android 上未捕获异常还会带走整个进程）。连接会被收干净并补一次
 * [onClosed]（`cause = ERROR`）。
 */
interface PeerListener {

    /** 连接已可用（可以立刻 [Peer.send]），发生在任何 [onLine] 之前。 */
    fun onOpen(peer: Peer) {}

    /** 收到一条消息。同一条连接的消息按发送顺序到达。 */
    fun onLine(peer: Peer, line: String) {}

    /** 连接断开，每条连接只回调一次。 */
    fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {}

    /** 只有 [TcpServer] 会调：accept 循环挂了。客户端不会调它。 */
    fun onServerDown(reason: String, error: Throwable?) {}
}
