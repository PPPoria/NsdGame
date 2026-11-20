package com.poria.net

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网络 IO 线程池：一条连接占一条线程（阻塞 IO 绕不开），空下来的线程 60 秒回收。
 *
 * 三个为什么：
 * - 不用 `:base` 的 ThreadPool：那个是有界队列 + AbortPolicy，队列满会抛
 *   `RejectedExecutionException`——对"每条连接必须有一条线程"的读循环来说是致命的。
 * - 不用 `Dispatchers.IO`：`:core` 是零依赖的纯 Kotlin 模块，引不了 coroutines。
 * - 用 cached 池而不是固定大小：并发上界是"同时有几条连接"（一局最多 6 人），
 *   把这个上界写进线程池参数只会得到一类难查的"写超时"。
 */
internal object NetThreads {

    private val seq = AtomicInteger(0)

    private val pool = Executors.newCachedThreadPool(
        ThreadFactory { runnable ->
            Thread(runnable, "nsdnet-${seq.incrementAndGet()}").apply {
                // daemon：忘关连接也不至于让 JVM 退不出去（Android 进程同理）
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    )

    fun execute(task: () -> Unit) {
        pool.execute { task() }
    }
}
