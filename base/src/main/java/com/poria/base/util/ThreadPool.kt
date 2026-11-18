package com.poria.base.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台线程池。
 *
 * 原实现是 `core = max = CPU 核数` + **无界队列**，于是 `maxPoolSize` 和 `keepAlive` 全是死配置：
 * 无界队列永远不会满，线程数永远停在 corePoolSize，超时也回收不了 core 线程。
 * 这里换成有界队列 + 明确的线程上限，任务排队满了会抛 RejectedExecutionException（配合有限线程更安全）。
 *
 * 走协程时优先用 [dispatcher]，不要自己 `withContext(Dispatchers.IO)` 再套一层线程池。
 */
object ThreadPool {

    private const val CORE_SIZE = 2
    private const val MAX_SIZE = 4
    private const val QUEUE_CAPACITY = 64
    private const val KEEP_ALIVE_SECONDS = 30L

    private val pool: ThreadPoolExecutor = ThreadPoolExecutor(
        CORE_SIZE,
        MAX_SIZE,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingDeque(QUEUE_CAPACITY),
        NamedThreadFactory("nsd-worker"),
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        // 允许 core 线程在空闲时回收，避免常驻线程白占内存
        allowCoreThreadTimeOut(true)
    }

    /** 协程调度器，可直接 `withContext(ThreadPool.dispatcher) { }`。 */
    val dispatcher: CoroutineDispatcher by lazy { pool.asCoroutineDispatcher() }

    fun execute(task: Runnable) {
        pool.execute(task)
    }

    private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(r: Runnable): Thread = Thread(r, "$prefix-${counter.getAndIncrement()}").apply {
            // 线程名带上前缀，抓 ANR/Systrace 时能一眼看出是哪个池
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
}
