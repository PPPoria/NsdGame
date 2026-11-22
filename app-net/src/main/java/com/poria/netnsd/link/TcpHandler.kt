package com.poria.netnsd.link

import kotlinx.serialization.json.JsonElement

/**
 * 链路上认出来的一个人：**只有身份，没有资料**。
 *
 * 昵称、头像这些东西不归链路管——上层（比如 `profile` 频道）自己广播、自己查。
 * 这里留下 [isHost] 是因为它是**链路事实**（回环连上来那条），上层推导不出来：
 * 房主标记只在服务端侧产生，客户端自称"我是房主"没有用。
 */
data class PlayerRef(
    val id: String,
    val isHost: Boolean,
)

/**
 * 一个频道的处理者。**TcpLink 只做路由**：`ns` 对上的负载原样丢进来，怎么处理是它的事。
 *
 * 回调线程：在**收到消息那条连接的读线程**上按序调用；不同连接之间可能并发，
 * 所以 Handler 自己负责线程安全（和 `Room` 用一把锁是同一个取舍）。
 */
interface TcpHandler {

    /** 频道名，注册表里唯一。不能是链路保留的 `_link`。 */
    val ns: String

    /**
     * 注册成功时调用一次，之后所有推送都走这个 [LinkOut]。
     *
     * 它绑定在本频道的 `ns` 上，Handler 发帧时不用自己填频道名。
     */
    fun attach(out: LinkOut) {}

    /**
     * 房主给本机分了身份：[localId] 就是本机在这条链路上的 `playerId`。
     *
     * 报到完成时调用一次（晚注册的频道在 [attach] 之后立刻补一次），早于任何业务回调。
     * 频道里需要"我是谁"就存下它——**不要自己生成 id**，身份只有一个来源：房主。
     */
    fun onLinked(localId: String) {}

    /** 有人报到了（房主自己走回环报到也算一次）。 */
    fun onPeerJoined(player: PlayerRef) {}

    /** 服务端方向：某个客人发来一条本频道的负载。 */
    fun onCommand(player: PlayerRef, payload: JsonElement?) {}

    /**
     * 客户端方向：房主推来一条本频道的负载（房主自己的回环客户端也会收到）。
     *
     * [from] 不为 `null` = 这条是**经房主中转**来的、由 [from] 那个玩家发的；
     * `null` = 房主自己推的（比如快照）。
     */
    fun onEvent(payload: JsonElement?, from: String?) {}

    /** 那个人走了（主动退出、被踢、心跳超时都算）。 */
    fun onPeerLeft(playerId: String) {}

    /** 本机这条链路断了 / 被关掉了。[reason] 是给人看的原因。 */
    fun onLinkClosed(reason: String?) {}
}

/**
 * Handler 的发件箱：帧的 `ns` 自动等于所属频道的 [TcpHandler.ns]。
 *
 * 所有发送都是**尽力而为**：对端已经断开就返回 `false` / 少算一个人，不会抛异常。
 */
interface LinkOut {

    /**
     * 发给某个玩家：房主侧直接投递，客人侧经房主中转（帧里带 `to`，落款由房主盖）。
     *
     * `false` = 没有这个人、或者连接已经断了（中转是尽力而为）。
     */
    fun sendTo(playerId: String, payload: JsonElement?): Boolean

    /** 发给所有已报到的人（含房主自己）。返回真正发出去几条。 */
    fun broadcast(payload: JsonElement?): Int

    /**
     * 每个玩家一份（隐藏信息用，比如各自的手牌）。
     * `build` 返回 `null` 表示这个人不给。
     */
    fun broadcastEach(build: (playerId: String) -> JsonElement?): Int

    /** 客户端方向：用本频道的 `ns` 发给房主。 */
    fun sendCommand(payload: JsonElement?): Boolean

    /**
     * 把某个玩家踢下线：先给对方发一条 BYE（带原因），再关掉那条连接。
     *
     * 只有房主侧有意义（客人手里没有别人的连接，返回 `false`）。
     * 对方会从链路的 BYE 里拿到原因，`onLinkClosed` 会带着它回调。
     */
    fun disconnect(playerId: String, reason: String): Boolean
}
