package com.poria.netnsd.room

import com.poria.netnsd.Session

/**
 * 房间动作（客户端 → 房主）：**只有命令，没有图片**。
 *
 * 图片是链路级的东西（`blob` 频道），跟房间没有关系，所以全在 `blob/BlobCommands.kt`：
 * 谁拥有某张图由**持有快照的那一层**自己算（房间页看 `players[].avatar`），
 * blob 频道只认 `(id, ownerId)`。
 *
 * 都是**扩展函数**而不是 `Session` 的方法：这样会话对象不用越长越胖，各业务的命令各写各的文件。
 * 全都是"发出去就不管了"：能不能生效由服务端说了算，结果以推回来的快照为准。
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
