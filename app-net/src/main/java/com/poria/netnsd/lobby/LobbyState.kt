package com.poria.netnsd.lobby

import com.poria.netnsd.link.TcpLink
import kotlinx.serialization.Serializable

/**
 * 服务器推给客户端的**权威快照**：谁在座、选了哪个游戏、怎么玩、聊天记录。
 *
 * 它就是 `lobby` 频道 `snap` 事件里那份 JSON 的本体（`@Serializable`），客户端**只渲染它，不做本地推演**：
 * 点按钮只是发命令，等这份快照回来才动界面。这样"名册/座次/设置"永远只有一份真相——服务器那份。
 *
 * **最小权威范围**：身份（id）、座位、聊天记录、房间设置。名字和头像不在这里——
 * 那是 [com.poria.netnsd.profile.ProfileChannel] 的事，它们不影响任何判定，
 * 也不该让一份快照卡住（头像晚到一会儿不该挡住建房/开局）。
 *
 * 加字段的代价：给新字段一个默认值即可，老客户端会被 `ignoreUnknownKeys` 忽略掉；
 * 删/改字段则要动 [LobbyProtocol.VERSION]。
 */
@Serializable
data class LobbySnapshot(
    val version: Int = LobbyProtocol.VERSION,
    val roomName: String,
    /** 服务器自己那条客户端连接的 id；空串 = 房间里暂时没有房主（房主那条连接断了）。 */
    val hostId: String,
    val phase: LobbyPhase,
    val gameId: String,
    /** 牌桌座位数（含空座）。座次就是将来的出牌顺序。 */
    val tableSize: Int,
    /**
     * 服务器算好的"现在还开不了局"的原因；空串 = 现在就能开始。
     *
     * 客户端**只负责显示**（比如显示在"开始游戏"按钮上），不自己再判一遍规则。
     * 这里不带人名——名字不归这个频道，所以是"还有 2 人没准备"这种说法。
     */
    val startHint: String = "",
    /** 自定义玩法开关：optionId → 开/关。 */
    val options: Map<String, Boolean>,
    /** 按座位排序。**只有 id 和座位**。 */
    val players: List<LobbyPlayer>,
    /** 准备好了的人（房主不需要准备，所以这里通常不含 hostId）。 */
    val readyIds: List<String> = emptyList(),
    /** 聊天记录（按 seq 递增）。快照太大时服务器会先丢最老的几条。 */
    val messages: List<LobbyMessage>,
) {

    /** 能不能开局。服务器说了算（[startHint] 为空且还在大厅）。 */
    val canStart: Boolean get() = phase == LobbyPhase.Lobby && startHint.isEmpty()

    fun player(id: String): LobbyPlayer? = players.firstOrNull { it.id == id }

    fun playerAt(seat: Int): LobbyPlayer? = players.firstOrNull { it.seat == seat }

    fun isReady(id: String): Boolean = id in readyIds

    fun isHost(id: String): Boolean = hostId.isNotEmpty() && hostId == id
}

/**
 * 房间里的一个人：**身份 + 座位，就这两样**。
 *
 * 昵称、头像在 `profile` 频道，准备状态在 [LobbySnapshot.readyIds]——
 * 它们都不参与"谁是几号位、谁能改设置"这类判定，所以不进这个类。
 */
@Serializable
data class LobbyPlayer(
    /** 服务器分配的身份，连接期间不变（座位会变，它不变）。 */
    val id: String,
    val seat: Int,
)

@Serializable
data class LobbyMessage(
    /** 服务器自增序号：UI 用它做列表 diff，内容相同的两条也不会被合并。 */
    val seq: Int,
    val playerId: String,
    val text: String,
)

/**
 * 房间本身处在哪个阶段。现在只会是 [Lobby]——对局规则还没写。
 *
 * 线上（快照里）存的是**枚举名**（`"Lobby"` / `"Playing"`），和 [id] 一致。
 */
enum class LobbyPhase(val id: String) {
    Lobby("Lobby"),
    Playing("Playing"),
}

/** 本机这条连接的状态。**注意**：它不是房间状态，房间状态全在 [LobbySnapshot] 里。 */
enum class SessionPhase {
    /** 正在开房 / 正在连别人。 */
    CONNECTING,

    /** 收到过至少一份快照，可以操作了。 */
    ONLINE,

    /** 连接断了、被拒了、被踢了。页面该提示/退出。 */
    CLOSED,
}

/**
 * UI 要渲染的东西 = 本机连接状态 + 服务器快照。
 *
 * `myId` 是服务器分配给本机的身份（`TcpLink.playerId`，经 `LobbyChannel.onLinked` 送进来），
 * 所以客户端不用等服务器"在快照里告诉我我是谁"就能知道哪一行是自己。
 */
data class LobbyState(
    /** 是不是本机开的房。只影响文案；能不能改设置由快照里的 [LobbySnapshot.hostId] 决定。 */
    val host: Boolean = false,
    val phase: SessionPhase = SessionPhase.CONNECTING,
    val myId: String = "",
    val snapshot: LobbySnapshot? = null,
    /** 给用户看的一句提示：连接失败、被拒绝、被踢、服务器的错误。 */
    val note: String? = null,
    /**
     * 本机缓存里**已经完整**的图片 id（下载拼完/自己上传就进来的）。
     *
     * 它只影响"能不能立刻画出来"：头像 id 如果在这里面，UI 直接取文件解码；
     * 不在就先把占位画上、发一次 `Session.requestImage`。
     */
    val availableBlobs: Set<String> = emptySet(),
) {

    val online: Boolean get() = phase == SessionPhase.ONLINE

    val myPlayer: LobbyPlayer? get() = snapshot?.player(myId)

    /** 我是不是这间房的房主——由服务器快照说了算，不是本机自己猜的。 */
    val iAmHost: Boolean get() = snapshot != null && myId.isNotEmpty() && snapshot.isHost(myId)

    val iAmReady: Boolean get() = snapshot?.isReady(myId) == true

    fun hasBlob(id: String): Boolean = id.isNotEmpty() && id in availableBlobs
}

/**
 * 把"链路状态 + 本机已经能画的图"合进大厅状态：客户端拼装层的最后一步。
 *
 * 大厅快照由 `lobby` 频道给，链路状态和图片可用性是另外两条流，只有 UI 需要它们叠在一起。
 * 这条注释就是它该放哪儿的答案：**拼装归 UI 层（VM），不归任何一个频道**。
 */
fun LobbyState.mergedWith(link: TcpLink.LinkState, blobs: Set<String>): LobbyState {
    val mergedNote = when {
        phase == SessionPhase.CLOSED -> note ?: link.note
        phase == SessionPhase.CONNECTING -> link.note
        else -> note
    }
    return copy(note = mergedNote, availableBlobs = blobs)
}
