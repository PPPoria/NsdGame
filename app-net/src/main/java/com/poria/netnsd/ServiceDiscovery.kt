package com.poria.netnsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 在局域网里找"谁开了牌局"。找到的 [FoundHost] 里就带着能直接连的 IP 和端口。
 *
 * ```
 * val discovery = ServiceDiscovery(context, this)   // this: Listener
 * discovery.start()                                 // onFound(host) → TcpClient(host.host, host.port, ...)
 * ...
 * discovery.stop()                                  // onDestroy / 离开搜索页时
 * ```
 *
 * ## 为什么内部是 channel + 挂起 for 循环
 *
 * NsdManager 同一时刻只允许一个 `resolveService` 在跑，第二个直接抛
 * `IllegalArgumentException("listener already in use")`，所以"发现"必须串行化。
 * 这件事**不用手工状态机**：`onServiceFound` 把服务 `trySend` 进 channel，
 * 一个收集协程用 `for (info in inbox)` 一条一条解析——`resolve` 是挂起函数，
 * 循环天然停在原地等它回来，于是"单飞"由语言保证，而不是由 `resolving: Boolean` 保证。
 *
 * 后果是**状态从 4 个减到 3 个，且没有一个需要手工复位**：
 * - `inbox`（channel）：跨线程入口，`trySend` 线程安全；
 * - `job`：收集协程。`stop()` 里 `cancel()` 一句，就把"停止入队、丢弃在飞的解析、
 *   结束循环"全办了——不用再清队列、不用再复位标志位；
 * - `known`：服务名 → 地址。**主线程独占**（收集协程跑在 Main），所以它不用锁。
 *
 * ## 另外三个必须知道的坑
 *
 * 1. **必须主线程调用**（[NsdManager] 要 Looper）；`onFound` / `onLost` / `onError` 也在主线程回来。
 * 2. **没有"刷新"**：想重扫只能 [stop] 再 [start]。
 * 3. 同一个服务会被反复发现（TXT 记录变了、路由器缓存），这里按服务名去重，
 *    只有地址真的变了才会再回调一次 [Listener.onFound]。
 */
class ServiceDiscovery(
    context: Context,
    private val listener: Listener,
) {

    /** 搜索事件。都有空实现，只覆盖需要的那个就行。全部在主线程回调。 */
    interface Listener {
        /** 发现（或地址变了）一台可连的机器。 */
        fun onFound(host: FoundHost) {}

        /** 某台机器消失了，参数是它的 [FoundHost.serviceName]。 */
        fun onLost(serviceName: String) {}

        /** 搜索/解析出问题。**不致命**，可以只在 debug 日志里打出来。 */
        fun onError(message: String) {}
    }

    private val nsd: NsdManager = requireNotNull(
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    ) { "这台设备没有 NSD 服务（android.net.nsd）" }

    /** 只有"系统回调线程 → 主线程"这一件事用它；主线程上的路径直接调用，不再多绕一跳。 */
    private val main = Handler(Looper.getMainLooper())

    // ── 状态：全部在主线程读写 ───────────────────────────────────────────────
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var inbox: Channel<NsdServiceInfo>? = null
    private var job: Job? = null
    private val known = HashMap<String, FoundHost>()

    val isRunning: Boolean get() = discoveryListener != null

    /**
     * 开始搜索。已经在搜索时重复调用什么都不做。
     *
     * @throws IllegalStateException 不在主线程调用
     */
    fun start() {
        requireMainThread()
        if (discoveryListener != null) return

        // UNLIMITED 而不是"满了丢旧的"：丢一条发现结果是正确性问题，
        // 而这里的生产者是系统、上限就是同一个局域网里的服务数量（个位数）。
        val newInbox = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val newListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // 不再按 serviceType 过滤：discoverServices 已经限定了类型，
                // 而个别机型返回的类型串带不带尾点并不一致，多滤一道就会一条都找不到。
                newInbox.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName
                main.post {
                    if (known.remove(name) != null) listener.onLost(name)
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 官方要求：start 失败也要 stop 一次，否则系统里那次注册留着，之后再也起不来
                main.post {
                    if (inbox !== newInbox) return@post // 上一轮的迟到回调，别动新一轮的状态
                    stopInternal()
                    listener.onError("开始搜索失败 errorCode=$errorCode")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post {
                    if (inbox !== newInbox) return@post
                    stopInternal()
                    listener.onError("停止搜索失败 errorCode=$errorCode")
                }
            }
        }

        discoveryListener = newListener
        inbox = newInbox

        // 唯一的消费者：串行解析。cancel() 就是"停止搜索"的全部善后。
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            for (info in newInbox) {
                try {
                    publish(resolve(info))
                } catch (e: CancellationException) {
                    throw e // 取消不是"解析失败"，别报给下游
                } catch (e: Throwable) {
                    listener.onError(e.message ?: "解析 ${info.serviceName} 失败")
                }
            }
        }

        try {
            nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, newListener)
        } catch (e: IllegalArgumentException) {
            // 例如别处已经占用了同一个 serviceType
            stopInternal()
            main.post { listener.onError("开始搜索失败：${e.message}") }
        }
    }

    /** 停止搜索并清空已发现列表。重复调用安全。 */
    fun stop() {
        requireMainThread()
        stopInternal()
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /**
     * 解析一条服务。失败抛 [IOException]，消息直接给 [Listener.onError] 看。
     *
     * 挂起期间**不会有第二条进来解析**——这正是我们要的"单飞"。
     */
    private suspend fun resolve(info: NsdServiceInfo): FoundHost =
        suspendCancellableCoroutine<FoundHost> { cont ->
            val resolveListener = object : NsdManager.ResolveListener {

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (!cont.isActive) return // 已经取消了：结果没人要，直接丢
                    val found = foundHostOf(info)
                    cont.resumeWith(
                        if (found != null) {
                            Result.success(found)
                        } else {
                            Result.failure(IOException("${info.serviceName} 没拿到可用地址/端口"))
                        }
                    )
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) {
                        cont.resumeWith(Result.failure(IOException("解析 ${info.serviceName} 失败 errorCode=$errorCode")))
                    }
                }
            }
            // 取消时把系统那边的解析也停掉，否则下一次搜索会撞上"listener already in use"
            cont.invokeOnCancellation { stopResolution(resolveListener) }

            try {
                startResolution(info, resolveListener)
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(IOException("解析 ${info.serviceName} 失败：${e.message}")))
                }
            }
        }

    /** 去重后再给下游：同一个服务只有地址变了才再回调一次。 */
    private fun publish(host: FoundHost) {
        if (known.put(host.serviceName, host) != host) listener.onFound(host)
    }

    /**
     * 取可连的地址。
     *
     * API 34 起 `getHost()` 被弃用、改成 `getHostAddresses()`（一个服务可能有多个地址，
     * 双栈设备经常同时给 IPv4 + 链路本地 IPv6）。优先 IPv4：局域网里 IPv6 多半连不通，
     * 拼起来还得加方括号。
     */
    @Suppress("DEPRECATION")
    private fun foundHostOf(info: NsdServiceInfo): FoundHost? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        val address = (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
        if (address == null || info.port <= 0) return null
        return FoundHost(serviceName = info.serviceName, host = address, port = info.port)
    }

    /**
     * 发起解析。
     *
     * API 34 起两参接口被弃用（老接口的回调线程由"调用线程有没有 Looper"决定），
     * 新接口让你显式给 Executor。这里统一用主线程。
     */
    @Suppress("DEPRECATION")
    private fun startResolution(info: NsdServiceInfo, resolveListener: NsdManager.ResolveListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsd.resolveService(info, Executor { main.post(it) }, resolveListener)
        } else {
            nsd.resolveService(info, resolveListener)
        }
    }

    /** 取消解析。`stopServiceResolution` 是 API 34 才有的，老系统只能等它回来再丢掉结果。 */
    private fun stopResolution(resolveListener: NsdManager.ResolveListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { nsd.stopServiceResolution(resolveListener) }
        }
    }

    // ── 内部状态 ────────────────────────────────────────────────────────────

    /** 停搜索的全部善后。只做一次：`discoveryListener` 是"是否在跑"的唯一依据。 */
    private fun stopInternal() {
        val listenerToStop = discoveryListener ?: return
        discoveryListener = null
        inbox = null
        known.clear()
        // 先取消收集协程：挂起中的 resolve 会走 invokeOnCancellation，
        // 顺手把系统那边的解析停掉，这样"停止入队 / 丢弃在飞的解析 / 结束循环"一次办完
        job?.cancel()
        job = null
        runCatching { nsd.stopServiceDiscovery(listenerToStop) }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ServiceDiscovery 只能在主线程调用（NsdManager 需要 Looper）"
        }
    }
}
