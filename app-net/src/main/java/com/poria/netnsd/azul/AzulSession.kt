package com.poria.netnsd.azul

import com.poria.netnsd.Session
import com.poria.netnsd.SlotKey
import com.poria.netnsd.lobby.LobbyPhase

/**
 * 对局频道在 [Session] 上是**怎么被持有、怎么开关**的。
 *
 * 为什么单独一个文件而不是写进 `Session.kt`：加一款游戏只该加一个文件。
 * 存储仍在 `Session` 里（一个按需开的槽位，`SlotKey` 当键），这里只有"开/关/读"三件事。
 *
 * 频道跟着**连接**活，不跟页面活：
 * - 对局页进来调 [ensureAzul]（幂等）开一次；
 * - 页面退出**不关**——房主那台机器上的 `AzulEngine` 就挂在这个实例上，关掉等于砸了全桌牌桌；
 * - 真正回收：回首页 `session.leave()`（连带清空槽位），或者将来的"结束对局回大厅"调 [closeAzul]。
 *
 * 再加一款游戏？把这个文件复制一份：换 key、换 `ensure` 里的角色判断，就完了。
 */

/** 槽位钥匙：给 `Session` 的槽位表认的类型化 key（`SlotKey<AzulChannel>`）。 */
internal object AzulSlot : SlotKey<AzulChannel>()

/** 当前对局频道；`null` = 还没开（或者已经关掉/收摊了）。只读，页面用它订阅局面。 */
val Session.azul: AzulChannel?
    get() = slot(AzulSlot)

/**
 * 确保对局频道开着（**幂等**，重复调用拿同一个实例）。
 *
 * - 服务器（房主那台机器）：带上引擎，按大厅座次开局，然后推一份局面；
 * - 纯客户端：只等着收局面（注册时会先要一份 `sync`）。
 *
 * 要求大厅已经切进 [LobbyPhase.Playing]——还没开局就进对局页是调用方的 bug，直接返回 `null`。
 *
 * @return `null` = 没连接 / 大厅还没开局 / 这个 `ns` 已被占用
 */
@Synchronized
fun Session.ensureAzul(): AzulChannel? {
    azul?.let { return it }
    val current = connection ?: return null
    val lobby = current.lobby
    val snapshot = lobby.state.value.snapshot ?: return null
    if (snapshot.phase != LobbyPhase.Playing) return null

    val seatOrder = if (lobby.state.value.iAmHost) {
        // 服务器侧的权威座次（座位号 = 出手顺序）
        lobby.snapshot()?.players?.sortedBy { it.seat }?.map { it.id }.orEmpty()
    } else {
        null
    }
    val channel = AzulChannel(seatOrder)
    if (!current.link.register(channel)) return null
    setSlot(AzulSlot, channel)
    return channel
}

/** 关掉对局频道（结束对局 / 重开一局时用；**不动链路**，其它频道照常）。 */
@Synchronized
fun Session.closeAzul() {
    val channel = azul ?: return
    setSlot(AzulSlot, null)
    runCatching { connection?.link?.unregister(channel.ns) }
}
