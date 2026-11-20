package com.poria.netnsd.room

import kotlinx.serialization.Serializable

/**
 * 服务端推给客户端的**权威快照**：房间里的一切（谁在、坐哪、选了哪个游戏、怎么玩、聊天记录）。
 *
 * 它就是线上那份 `SNAP|{...}` 的 JSON 本体（`@Serializable`），客户端**只渲染它，不做本地推演**：
 * 点按钮只是发命令，等这份快照回来才动界面。这样"花名册/座次/设置"永远只有一份真相——服务端那份。
 *
 * 加字段的代价：给新字段一个默认值即可，老客户端会被 `ignoreUnknownKeys` 忽略掉；
 * 删/改字段则要动 [RoomProtocol.VERSION]。
 */
@Serializable
data class RoomSnapshot(
    val version: Int = RoomProtocol.VERSION,
    val roomName: String,
    /** 房主的 playerId；空串 = 房间里暂时没有房主（房主那条连接断了）。 */
    val hostId: String,
    val phase: RoomPhase,
    val gameId: String,
    /** 牌桌座位数（含空座）。座次就是将来的出牌顺序。 */
    val tableSize: Int,
    /**
     * 服务端算好的"现在还开不了局"的原因；空串 = 现在就能开始。
     *
     * 客户端**只负责显示**（比如显示在"开始游戏"按钮上），不自己再判一遍规则。
     */
    val startHint: String = "",
    /** 自定义玩法开关：optionId → 开/关。 */
    val options: Map<String, Boolean>,
    /** 按座位排序。 */
    val players: List<RoomPlayer>,
    /** 聊天记录（按 seq 递增）。快照太大时服务端会先丢最老的几条。 */
    val messages: List<RoomMessage>,
) {

    /** 能不能开局。服务端说了算（[startHint] 为空且还在房间里）。 */
    val canStart: Boolean get() = phase == RoomPhase.Lobby && startHint.isEmpty()

    fun player(id: String): RoomPlayer? = players.firstOrNull { it.id == id }

    fun playerAt(seat: Int): RoomPlayer? = players.firstOrNull { it.seat == seat }

    val host: RoomPlayer? get() = players.firstOrNull { it.host }
}

@Serializable
data class RoomPlayer(
    /** 客户端自己生成、服务端认可的身份，连接期间不变（座位会变，它不变）。 */
    val id: String,
    val seat: Int,
    val name: String,
    val host: Boolean,
    val ready: Boolean,
    /**
     * 头像的内容 id（`sha1(图片)` 的十六进制，或者 `builtin:` 开头的打包素材）。
     *
     * 空串 = 没设头像。**快照里只有 id、没有图片本体**：要显示就按 id 去 [RoomSession.blobFile]
     * 拿缓存，没有就 [RoomSession.requestBlob] 问房主要。
     */
    val avatar: String = "",
)

@Serializable
data class RoomMessage(
    /** 服务端自增序号：UI 用它做列表 diff，内容相同的两条也不会被合并。 */
    val seq: Int,
    val playerId: String,
    val text: String,
)

/**
 * 房间本身处在哪个阶段。现在只会是 [Lobby]——对局规则还没写。
 *
 * 线上（快照里）存的是**枚举名**（`"Lobby"` / `"Playing"` / `"UseBefore"` …），和 [id] 一致；
 * [id] 留给将来的规则引擎做前缀判断（`id.startsWith("Draw")` 之类）。
 */
enum class RoomPhase(val id: String) {
    Lobby("Lobby"),
    Playing("Playing"),

    // 玩家阶段的设计哲学：After后缀，就表示这个阶段已经执行完了，不可更改了（例如摸牌数、伤害数等等）；
    // 那么Before的意义是什么呢？是用来和无后缀的阶段区分——有Before的阶段，允许更改（例如摸牌数、伤害数等等），没有后缀的则不允许，只是普通指定，用来给id检测前缀

    PlayerBegin("PlayerBegin"), // 某个玩家阶段开始
    JudgeBefore("JudgeBefore"),
    Judge("Judge"), // 判定阶段
    JudgeAfter("JudgeAfter"),
    DrawBefore("DrawBefore"),
    Draw("Draw"), // 摸牌阶段
    DrawAfter("DrawAfter"),
    UseBefore("UseBefore"),
    Use("Use"), // 出牌阶段
    UseAfter("UseAfter"),
    DiscardBefore("DiscardBefore"),
    Discard("Discard"), // 弃牌阶段
    DiscardAfter("DiscardAfter"),
    PlayerEnd("PlayerEnd"), // 某个玩家阶段结束

    DamageBefore("DamageBefore"),
    Damage("Damage"), // 受伤
    DamageAfter("DamageAfter"),
    RecoverBefore("RecoverBefore"),
    Recover("Recover"), // 恢复
    RecoverAfter("RecoverAfter"),
}

/** 本机这条连接的状态。**注意**：它不是房间状态，房间状态全在 [RoomSnapshot] 里。 */
enum class SessionPhase {
    /** 正在开房 / 正在连别人。 */
    CONNECTING,

    /** 收到过至少一份快照，可以操作了。 */
    ONLINE,

    /** 连接断了、被拒了、被踢了。页面该提示/退出。 */
    CLOSED,
}

/**
 * UI 要渲染的东西 = 本机连接状态 + 服务端快照。
 *
 * `myId` 是本机自己生成的（[RoomProtocol.join] 报给服务端），所以客户端不用等服务端
 * "告诉我我是谁"就能知道快照里哪一行是自己。
 */
data class RoomState(
    /** 是不是本机开的房。只影响文案；能不能改设置由快照里的 [RoomSnapshot.hostId] 决定。 */
    val host: Boolean = false,
    val phase: SessionPhase = SessionPhase.CONNECTING,
    val myId: String = "",
    val snapshot: RoomSnapshot? = null,
    /** 给用户看的一句提示：连接失败、被拒绝、被踢、服务端的错误。 */
    val note: String? = null,
    /**
     * 本机缓存里**已经完整**的图片 id（下载拼完/自己上传就进来的）。
     *
     * 它只影响"能不能立刻画出来"：快照里的头像 id 如果在这里面，UI 直接取文件解码；
     * 不在就先把占位画上、发一次 [RoomSession.requestBlob]。
     */
    val availableBlobs: Set<String> = emptySet(),
) {

    val online: Boolean get() = phase == SessionPhase.ONLINE

    val myPlayer: RoomPlayer? get() = snapshot?.player(myId)

    /** 我是不是这间房的房主——由服务端快照说了算，不是本机自己猜的。 */
    val iAmHost: Boolean get() = snapshot != null && myId.isNotEmpty() && snapshot.hostId == myId

    val iAmReady: Boolean get() = myPlayer?.ready == true

    fun hasBlob(id: String): Boolean = id.isNotEmpty() && id in availableBlobs
}
