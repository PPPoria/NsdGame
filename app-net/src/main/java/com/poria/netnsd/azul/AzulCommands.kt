package com.poria.netnsd.azul

import com.poria.game.azul.Tile
import com.poria.netnsd.Session

/**
 * 对局动作（客户端 → 服务器）。
 *
 * 和大厅一样：扩展函数，不是 `Session` 的方法；发出去就不管了，
 * 结果以服务器推回来的局面为准（不合法的话会收到一句 `rejected`）。
 *
 * 选砖的两种动作对应引擎的两个入口，其它判定（是不是你的回合、这行放不放得下、
 * 花色对不对）全在服务器侧——客户端**不要**自己判一遍。
 *
 * 也**没有**"这一轮结束"的命令：拿完最后一块砖后，服务器自己结算、
 * 停一会儿再开下一轮（`AzulEngine.nowCanFinishRound` → `finishRound()` → `nextRoundOrFinishGame()`）。
 */

/** 从工厂盘拿 [tile] 放 [row] 行；[plate] 是那个盘子上的全部砖（引擎靠它认盘子）。 */
fun Session.takeFromPlate(row: Int, tile: Tile, plate: List<Tile>) =
    sendAzul(AzulProtocol.Command.TakeFromPlate(row, tile, plate))

/** 从中央区拿 [tile] 放 [row] 行。 */
fun Session.takeFromRedundant(row: Int, tile: Tile) =
    sendAzul(AzulProtocol.Command.TakeFromRedundant(row, tile))

// 频道的开/关/取在 `AzulSession.kt`（`ensureAzul` / `closeAzul` / `azul`）：
// 它跟着连接活（房主的引擎在里面），所以不能由页面随手 new 一个再随手扔掉。

private fun Session.sendAzul(command: AzulProtocol.Command) {
    val connection = connection ?: return
    connection.link.send(AzulProtocol.NS, AzulProtocol.encode(command))
}
