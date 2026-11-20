package com.poria.net

import org.junit.Assert.assertNotNull
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 测试用的连接事件记录器：把回调塞进队列，测试线程再从队列里"等"。
 *
 * 这样既不忙等，也不会因为回调来得慢而假通过。房主那条回环连接和远端客人用的是同一个
 * 记录器——这本身就是"两者对下游没有区别"的证据。
 */
class RecordingListener : PeerListener {

    val opened = LinkedBlockingQueue<Peer>()

    val lines = LinkedBlockingQueue<String>()

    val closed = LinkedBlockingQueue<Closed>()

    val down = LinkedBlockingQueue<String>()

    override fun onOpen(peer: Peer) {
        opened += peer
    }

    override fun onLine(peer: Peer, line: String) {
        lines += line
    }

    override fun onClosed(peer: Peer, cause: CloseCause, reason: String?) {
        closed += Closed(cause, reason)
    }

    override fun onServerDown(reason: String, error: Throwable?) {
        down += reason
    }
}

data class Closed(val cause: CloseCause, val reason: String?)

/** 最多等 3 秒，等不到就让测试失败（而不是让整个 JVM 挂在那里）。 */
fun <T> LinkedBlockingQueue<T>.next(what: String): T {
    val value = poll(3, TimeUnit.SECONDS)
    assertNotNull("3 秒内没等到 $what", value)
    return value!!
}
