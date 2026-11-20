package com.poria.netnsd

/**
 * 服务类型。同一局域网里**只有用同一串**的才能互相发现，改这里要么一起改，要么谁都搜不到。
 *
 * 规则（mDNS / DNS-SD）：下划线开头、下划线分隔、点结尾，形如 `_服务名._传输层.`。
 * `_tcp` 表示我们用的是 TCP（对应 :core 的 [com.poria.net.TcpServer] / [com.poria.net.TcpClient]）。
 */
const val NSD_SERVICE_TYPE = "_ppporiagames._tcp."

/**
 * 局域网里发现的一台机器，也就是"一个可以连进去的牌局"。
 *
 * [host] + [port] 直接喂给 :core 的 `TcpClient`：
 * ```
 * TcpClient(host.host, host.port, listener).connect()
 * ```
 *
 * [serviceName] 是房主起的名（可能被系统改名成 "茯苓的牌桌 (2)"），拿来给人看；
 * 它也是区分"是不是同一个房"的 key（见 [ServiceDiscovery.Listener.onLost]）。
 */
data class FoundHost(
    val serviceName: String,
    /** IPv4 地址字符串，例如 `192.168.1.7`。 */
    val host: String,
    val port: Int,
)
