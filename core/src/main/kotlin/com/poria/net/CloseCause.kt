package com.poria.net

/**
 * 一条连接为什么会断。
 *
 * 下游靠它决定"要不要重连"：`LOCAL` / `REMOTE` 是意料之中的（回菜单、对面退出），
 * `ERROR` 才是真的出事了（对面发坏包、网络断了）。
 */
enum class CloseCause {
    /** 本机调了 [Peer.close] / [TcpServer.stop]。 */
    LOCAL,

    /** 对端关掉了连接（读到流结束）。 */
    REMOTE,

    /** 网络或协议出错，例如对端发来超过 [Wire.MAX_LINE_BYTES] 的一行。 */
    ERROR,
}
