package com.poria.netnsd.lobby

import com.poria.netnsd.Session

/**
 * 大厅动作（客户端 → 服务器）。
 *
 * 都是**扩展函数**而不是 `Session` 的方法：这样会话对象不用越长越胖，各业务的命令各写各的文件。
 * 全都是"发出去就不管了"：能不能生效由服务器说了算，结果以推回来的快照为准。
 *
 * 图片不在这里（`blob/BlobCommands.kt`），名字头像也不在这里（`profile/`）。
 */

fun Session.say(text: String) {
    if (text.isBlank()) return
    send(LobbyProtocol.Command.Say(text))
}

fun Session.setReady(ready: Boolean) = send(LobbyProtocol.Command.Ready(ready))

fun Session.takeSeat(seat: Int) = send(LobbyProtocol.Command.TakeSeat(seat))

fun Session.swapSeats(a: Int, b: Int) = send(LobbyProtocol.Command.SwapSeats(a, b))

fun Session.setTableSize(size: Int) = send(LobbyProtocol.Command.SetTableSize(size))

fun Session.selectGame(gameId: String) = send(LobbyProtocol.Command.SelectGame(gameId))

fun Session.setOption(optionId: String, on: Boolean) =
    send(LobbyProtocol.Command.SetOption(optionId, on))

fun Session.kick(playerId: String) = send(LobbyProtocol.Command.Kick(playerId))

fun Session.start() = send(LobbyProtocol.Command.Start)

private fun Session.send(command: LobbyProtocol.Command) {
    val connection = connection ?: return
    connection.link.send(LobbyProtocol.NS, LobbyProtocol.encode(command))
}
