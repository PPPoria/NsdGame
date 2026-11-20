package com.poria.netnsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.concurrent.Executor

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
 * 坑都在这里了，别再踩：
 * 1. **解析必须排队**：NsdManager 同一时刻只允许一个 resolve 在跑，第二个直接抛
 *    IllegalArgumentException（"listener already in use"）。所以 onServiceFound 只入队，
 *    由内部的单飞队列一条一条解析。
 * 2. **必须主线程调用**（[NsdManager] 要 Looper）；回调统一投递到主线程。
 * 3. **没有"刷新"**：想重扫只能 [stop] 再 [start]。
 * 4. 同一个服务可能被重复发现（TXT 记录变了、路由器缓存），内部按服务名去重，
 *    只有地址真的变了才会再回调一次 [Listener.onFound]。
 */
class ServiceDiscovery(
    context: Context,
    private val listener: Listener,
) {

    /** 搜索事件。都有空实现，只覆盖需要的那个就行。 */
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

    /** 回调统一回主线程：NsdManager 的回调线程不保证是主线程。 */
    private val main = Handler(Looper.getMainLooper())

    private val lock = Any()

    /** 等解析的服务。NsdManager 单飞，所以必须排队。 */
    private val pending = ArrayDeque<NsdServiceInfo>()

    /** 已经发现过的：服务名 → 地址。用来去重，也用来算"地址变了"。 */
    private val known = HashMap<String, FoundHost>()

    private var resolving = false

    @Volatile
    private var discovery: NsdManager.DiscoveryListener? = null

    val isRunning: Boolean get() = discovery != null

    /**
     * 开始搜索。已经在搜索时重复调用什么都不做。
     *
     * @throws IllegalStateException 不在主线程调用
     */
    fun start() {
        requireMainThread()
        if (discovery != null) return

        val discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // 这里不再按 serviceType 过滤：discoverServices 已经限定了类型，
                // 而个别机型返回的类型串带不带尾点并不一致，多滤一道就会一条都找不到。
                enqueue(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName
                val forgotten = synchronized(lock) { known.remove(name) != null }
                if (forgotten) main.post { listener.onLost(name) }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 官方要求：start 失败也要调一次 stopServiceDiscovery，
                // 否则系统里那次注册留着，之后再也起不来
                shutdown()
                main.post { listener.onError("开始搜索失败 errorCode=$errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                shutdown()
                main.post { listener.onError("停止搜索失败 errorCode=$errorCode") }
            }
        }

        // 先存再调：回调只会在这之后到来
        discovery = discoveryListener
        try {
            nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: IllegalArgumentException) {
            // 例如别处已经占用了同一个 serviceType
            discovery = null
            main.post { listener.onError("开始搜索失败：${e.message}") }
        }
    }

    /** 停止搜索并清空已发现列表。重复调用安全。 */
    fun stop() {
        requireMainThread()
        val wasRunning = discovery != null
        shutdown()
        if (wasRunning) synchronized(lock) { known.clear() }
    }

    // ── 解析队列 ────────────────────────────────────────────────────────────

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(lock) {
            if (discovery == null) return
            pending.addLast(info)
        }
        resolveNext()
    }

    /** 取出一个待解析的服务去解析；解析完成的回调里再调一次，形成"一条一条来"的队列。 */
    private fun resolveNext() {
        var next: NsdServiceInfo? = null
        synchronized(lock) {
            if (discovery == null || resolving) return
            next = pending.removeFirstOrNull()
            if (next != null) resolving = true
        }
        val target = next ?: return

        val resolveListener = object : NsdManager.ResolveListener {

            override fun onServiceResolved(info: NsdServiceInfo) {
                synchronized(lock) { resolving = false }
                publish(info)
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { resolving = false }
                main.post { listener.onError("解析 ${info.serviceName} 失败 errorCode=$errorCode") }
                resolveNext()
            }
        }

        try {
            resolve(target, resolveListener)
        } catch (e: IllegalArgumentException) {
            // 真撞上"listener already in use"就丢掉这一条，别把整轮搜索搞死
            synchronized(lock) { resolving = false }
            main.post { listener.onError("解析 ${target.serviceName} 失败：${e.message}") }
            resolveNext()
        }
    }

    /**
     * 解析一个服务。
     *
     * API 34 起老的两参接口被弃用：老接口的回调线程由"调用线程有没有 Looper"决定，
     * 新接口让你显式给 Executor。这里统一用主线程。
     */
    @Suppress("DEPRECATION")
    private fun resolve(target: NsdServiceInfo, resolveListener: NsdManager.ResolveListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsd.resolveService(target, Executor { main.post(it) }, resolveListener)
        } else {
            nsd.resolveService(target, resolveListener)
        }
    }

    /** 把解析结果去重后交给下游。 */
    private fun publish(info: NsdServiceInfo) {
        val address = addressOf(info)
        if (address == null || info.port <= 0) {
            main.post { listener.onError("解析 ${info.serviceName} 失败：没拿到可用地址/端口") }
            return
        }
        val host = FoundHost(serviceName = info.serviceName, host = address, port = info.port)
        val isNew = synchronized(lock) { known.put(host.serviceName, host) != host }
        if (isNew) main.post { listener.onFound(host) }
    }

    /**
     * 取可连的地址。
     *
     * API 34 起 `getHost()` 被弃用、改成 `getHostAddresses()`（一个服务可能有多个地址，
     * 双栈设备上经常同时给 IPv4 + 链路本地 IPv6）。优先 IPv4：局域网对局里 IPv6 地址
     * 常常连不通，而且拼起来还得加方括号。
     */
    @Suppress("DEPRECATION")
    private fun addressOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        val picked = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return picked?.hostAddress
    }

    // ── 内部状态 ────────────────────────────────────────────────────────────

    private fun shutdown() {
        val listenerToStop = discovery ?: return
        discovery = null
        synchronized(lock) {
            pending.clear()
            // 解析中的那条的回调可能再也不会来了，标志位必须手动复位
            resolving = false
        }
        runCatching { nsd.stopServiceDiscovery(listenerToStop) }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ServiceDiscovery 只能在主线程调用（NsdManager 需要 Looper）"
        }
    }
}
