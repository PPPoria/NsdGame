package com.poria.netnsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/**
 * 把"我开了个牌局"广播到局域网：NSD 注册一个 [NSD_SERVICE_TYPE] 服务，顺带把 TCP 端口写进去。
 *
 * ```
 * val server = TcpServer(0, serverListener).start()      // :core，系统分配的端口
 * val advertiser = ServiceAdvertiser(context, this)      // this: Listener
 * advertiser.register("茯苓的牌桌", server.localPort)     // 端口交给 NSD 广播
 * ...
 * advertiser.unregister()                                // onDestroy / 回菜单时
 * ```
 *
 * 三条约束，踩过才知道：
 * 1. **必须在主线程调**（[NsdManager] 要 Looper），回调用 [Handler] 统一投递到主线程。
 * 2. 名字**可能被系统改名**：局域网里已有同名服务时，Android 会补成 `茯苓的牌桌 (2)`，
 *    以 [Listener.onRegistered] 给出的 [NsdServiceInfo.getServiceName] 为准。
 * 3. 同一个 app 进程里**不要同时开两个实例**广播同一个服务，系统会报 FAILURE_ALREADY_ACTIVE。
 */
class ServiceAdvertiser(
    context: Context,
    private val listener: Listener,
) {

    /** 广播事件。都有空实现，只覆盖需要的那个就行。 */
    interface Listener {
        /** 注册成功，可以开始等玩家连进来了。 */
        fun onRegistered(serviceName: String, port: Int) {}

        /** 注册/注销失败，`reason` 里带 `errorCode`。 */
        fun onFailed(reason: String) {}

        /** 注销完成。 */
        fun onUnregistered() {}
    }

    private val nsd: NsdManager = requireNotNull(
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    ) { "这台设备没有 NSD 服务（android.net.nsd）" }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var registration: NsdManager.RegistrationListener? = null

    /** 实际生效的服务名（可能被系统改过）；没在广播时是 null。 */
    @Volatile
    var serviceName: String? = null
        private set

    val isRegistered: Boolean get() = registration != null

    /**
     * 开始广播。
     *
     * @param name 给人看的牌局名（重名时系统会自动补后缀）
     * @param port 本机 TCP 端口，通常就是 `TcpServer.localPort`
     *
     * @throws IllegalStateException 已经在广播了
     * @throws IllegalArgumentException 名字为空或端口不合法
     */
    fun register(name: String, port: Int) {
        requireMainThread()
        require(name.isNotBlank()) { "牌局名不能为空" }
        require(port in 1..65535) { "端口不合法：$port（是不是忘了先 TcpServer.start()？）" }
        check(registration == null) { "已经在广播了，先 unregister()" }

        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = NSD_SERVICE_TYPE
            setPort(port)
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                serviceName = info.serviceName
                main.post { listener.onRegistered(info.serviceName, info.port) }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registration = null
                serviceName = null
                main.post { listener.onFailed("广播失败 errorCode=$errorCode（端口 ${info.port}）") }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registration = null
                serviceName = null
                main.post { listener.onUnregistered() }
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // 系统那边已经没这条注册了，本机状态跟着清掉，否则再也注册不回来
                registration = null
                serviceName = null
                main.post { listener.onFailed("停止广播失败 errorCode=$errorCode") }
            }
        }

        // 先存再调：回调只会在这之后到来
        registration = registrationListener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: IllegalArgumentException) {
            registration = null
            serviceName = null
            main.post { listener.onFailed("广播失败：${e.message}") }
        }
    }

    /** 停止广播。没在广播时什么都不做。重复调用安全。 */
    fun unregister() {
        requireMainThread()
        val listenerToStop = registration ?: return
        try {
            nsd.unregisterService(listenerToStop)
        } catch (e: IllegalArgumentException) {
            registration = null
            serviceName = null
            main.post { listener.onFailed("停止广播失败：${e.message}") }
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ServiceAdvertiser 只能在主线程调用（NsdManager 需要 Looper）"
        }
    }
}
