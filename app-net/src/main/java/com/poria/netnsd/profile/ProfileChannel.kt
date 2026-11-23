package com.poria.netnsd.profile

import com.poria.netnsd.link.LinkOut
import com.poria.netnsd.link.PlayerRef
import com.poria.netnsd.link.TcpHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * `profile` 频道（`ns = "profile"`）：**个人资料**（昵称 / 签名 / 头像 id）。
 *
 * ```
 * 我推：    out.sendCommand(Put(profile))            → 服务器盖 id 后广播 Info
 * 我拉：    out.sendTo(id, Want(id))                 → 本人收到 → 回 Info（可以不回）
 * 收到：    Info → 记进 profiles（是谁的资料由 Info.id 说，落款只用来防伪）
 * ```
 *
 * ## 为什么单独一个频道
 *
 * 房间的权威范围只有"身份 / 座位 / 聊天 / 房间设置"。资料不参与任何判定，
 * 却最容易被"一次快照"卡住：以前昵称和头像 id 塞在房间快照里，头像还没装好就进来了，
 * 别人只能等**下一份快照**才知道有头像。拆出来之后资料什么时候就绪什么时候走，
 * 和房间快照互不干涉。
 *
 * ## 推和拉各管一半
 *
 * - **推**（[publish]）用在"我的资料变了"：改了头像再发一次，所有人当场更新；
 * - **拉**（[want] / [sync]）用在"我刚进来，名册上这些人都不认识"：按 id 去问，
 *   **对方可以选择不答**（不想公开就不回；问的人一直显示占位，这就是匿名模式）；
 * - 两条路的回答都是 `info`，所以接收侧只有一条路径。
 *
 * ## 权威归谁
 *
 * 归**本人**：`put` / `want` 里都没有"我是谁"，服务器用连接上认到的 id 覆盖落款。
 * 服务器**不存资料**——它只做两件事：把 `put` 变成广播、把 `want` 转给本人。
 *
 * ## 线程
 *
 * 回调和别的频道一样，跑在收到消息那条连接的读线程上；这里没有磁盘 IO、没有长耗时，
 * 也没有超时器（"对方不回"是常态，不需要计时），所以不需要额外的 scope。
 */
class ProfileChannel internal constructor() : TcpHandler {

    override val ns: String = NS

    private val lock = Any()

    @Volatile
    private var out: LinkOut? = null

    /** 本机身份（服务器分的）；还没报到就是空串。 */
    @Volatile
    private var myId: String = ""

    /** 我自己的资料：拉我的时候拿它回答，不答 = 匿名。 */
    @Volatile
    private var myProfile: Profile? = null

    /** 已经知道的资料（自己那份也在里面）。 */
    private val _profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val profiles: StateFlow<Map<String, Profile>> = _profiles.asStateFlow()

    /**
     * 已经问过、还没被回答的人。
     *
     * 用来去重：名册一有变化就 [sync] 一次，同一个 id 不会被问第二遍；
     * 对方不回就一直挂着（不会变成请求风暴），他哪天 `put` 了推过来就自然清掉。
     */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    // ── 本机 API ────────────────────────────────────────────────────────────

    /** 某个人的资料；还不知道就是 `null`（UI 先显示占位）。 */
    fun profile(id: String): Profile? = _profiles.value[id]

    /**
     * 报上/更新**自己的**资料。重复调用就是更新（换了头像再发一次，所有人立刻看到）。
     *
     * 图片本体不走这里：先 `blob.install(bytes)` 拿到 id，再把 id 放进 [Profile] 报上来。
     */
    fun publish(profile: Profile) {
        val clean = ProfileLimits.sanitize(profile)
        myProfile = clean
        out?.sendCommand(ProfileCodec.encode(ProfilePayload.Put(clean)))
    }

    /**
     * 向本人要一次资料。本机已有、正在问、要的是自己，都直接返回 `false`。
     *
     * 对方**可以不回**（匿名）：那就一直显示占位，也不会自动重问。
     */
    fun want(playerId: String): Boolean {
        if (playerId.isEmpty() || playerId == myId) return false
        if (_profiles.value.containsKey(playerId)) return false
        synchronized(lock) {
            if (playerId in _pending.value) return false
            _pending.update { it + playerId }
        }
        val sent = out?.sendTo(playerId, ProfileCodec.encode(ProfilePayload.Want(playerId))) == true
        if (!sent) forget(playerId)
        return sent
    }

    /**
     * 名册变了一次：把还不认识的 id 都问一遍。断开/没资料的人留在 [pending] 里，不会重复问。
     */
    fun sync(rosterIds: Collection<String>) {
        rosterIds.forEach { want(it) }
    }

    override fun attach(out: LinkOut) {
        this.out = out
    }

    override fun onLinked(localId: String) {
        myId = localId
        // 之前问过"自己"是没意义的（身份到得比名册晚），清掉
        forget(localId)
    }

    // ── 服务器侧：只做转换和中转 ─────────────────────────────────────────────

    override fun onCommand(player: PlayerRef, payload: JsonElement?) {
        when (val command = ProfileCodec.decode(payload) ?: return) {
            // 推：盖上是"谁发的"，然后广播给所有人（含发布者自己那条回环客户端）
            is ProfilePayload.Put -> {
                val info = ProfilePayload.Info(player.id, ProfileLimits.sanitize(command.profile))
                out?.broadcast(ProfileCodec.encode(info))
            }

            // 拉：理论上都带 to（链路直接中转给本人），走到这里说明没填收件人，忽略
            is ProfilePayload.Want -> Unit

            // info 是服务器发出去的，客户端不该往服务器发
            is ProfilePayload.Info -> Unit
        }
    }

    override fun onPeerLeft(playerId: String) {
        forget(playerId)
    }

    // ── 客户端侧 ────────────────────────────────────────────────────────────

    override fun onEvent(payload: JsonElement?, from: String?) {
        when (val event = ProfileCodec.decode(payload) ?: return) {
            // 有人来问我的资料：愿意公开就答，不想给就不吭声（这就是匿名模式）
            is ProfilePayload.Want -> answer(event.playerId, from)

            // 推来的 / 答回来的资料
            is ProfilePayload.Info -> accept(event, from)

            is ProfilePayload.Put -> Unit // 服务器侧才处理的负载
        }
    }

    override fun onLinkClosed(reason: String?) {
        synchronized(lock) {
            myId = ""
            myProfile = null
        }
        _profiles.value = emptyMap()
        _pending.value = emptySet()
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 有人问我：只答"我自己的"，而且只在我说过话（[publish] 过）以后答。 */
    private fun answer(targetId: String, asker: String?) {
        val me = myId
        val mine = myProfile ?: return // 没报过资料 = 不公开
        if (asker == null || asker == me) return
        if (targetId != me) return
        out?.sendTo(asker, ProfileCodec.encode(ProfilePayload.Info(me, mine)))
    }

    /**
     * 收一份资料。
     *
     * 只认两种落款：`from == null`（服务器广播）或 `from == info.id`（本人回答）。
     * 中转帧的落款由服务器盖章，所以"某某帮你带话"这种伪造到不了这里。
     */
    private fun accept(info: ProfilePayload.Info, from: String?) {
        if (from != null && from != info.id) return

        val clean = ProfileLimits.sanitize(info.profile)
        if (info.id == myId && from == null) myProfile = clean // 自己的资料被广播回来，顺手对齐
        // 先撤掉 pending 再写资料：订阅者看到"资料到了"的时候，"问过了"一定已经是清掉的
        forget(info.id)
        _profiles.update { it + (info.id to clean) }
    }

    private fun forget(playerId: String) {
        synchronized(lock) {
            if (playerId !in _pending.value) return
            _pending.update { it - playerId }
        }
    }

    companion object {
        /** 频道名：`TcpLink.register(ProfileChannel())` 用。 */
        const val NS = "profile"
    }
}
