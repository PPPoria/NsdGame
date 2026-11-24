package com.poria.netnsd

import android.content.Context
import com.poria.netnsd.blob.BlobChannel
import com.poria.netnsd.blob.BlobStore
import com.poria.netnsd.link.TcpLink
import com.poria.netnsd.lobby.LobbyChannel
import com.poria.netnsd.lobby.LobbyProtocol
import com.poria.netnsd.lobby.LobbyState
import com.poria.netnsd.lobby.mergedWith
import com.poria.netnsd.profile.Profile
import com.poria.netnsd.profile.ProfileChannel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 一条连接上的常驻频道：链路、图片、资料、大厅。
 *
 * 它们同生共死（链路一关，频道就没地方发帧了），所以绑在一起；
 * 它**不是**"会话门面"：没有命令方法、不拼状态，页面要做什么都自己拿这几个对象去做。
 *
 * 构造器是 `internal` 的：只有 `:app-net` 里的 [Session] 能造出它，页面伪造不了。
 */
class Connection internal constructor(
    val link: TcpLink,
    val blob: BlobChannel,
    /** 昵称 / 签名 / 头像 id——显示用的，不参与任何判定。 */
    val profile: ProfileChannel,
    val lobby: LobbyChannel,
) {

    /**
     * 当前该画的状态：大厅快照 + 链路状态 + 已经能画的图。
     *
     * UI 层订阅时用它做起手值（几股流各取一次当前值），免得第一帧闪一下"连接中"。
     * 资料表在 `profile.profiles` 里，由 VM 一起 combine。
     */
    val initialState: LobbyState
        get() = lobby.state.value.mergedWith(link.state.value, blob.available.value)
}

/**
 * 进程级的连接持有者：**App 里只放一个**（`App.session`）。
 *
 * ```
 * App
 *  └─ Session ──┬─ Connection（当前这一条：link + blob + lobby）
 *               └─ ServiceAdvertiser（房主才有）
 * ```
 *
 * 为什么不是页面持有：socket 和 NSD 广播的寿命比任何一个页面长——房间页进对局页时它们必须活着。
 * 页面之间**不传递链路**，谁需要谁从 `App.session.connection` 拿；一个页面走了只是它不再订阅。
 *
 * 为什么不是"什么都管"的会话：这里**只有**建/关和对资源的持有。
 * 命令发送在各频道的 `…Commands.kt`（扩展函数），状态拼装在 VM，图片进度在 `BlobChannel` 自己身上。
 *
 * ## 频道怎么归 Session 持有（而不把 Session 写成杂物间）
 *
 * - **每条连接必有的**（`link` / `blob` / `profile` / `lobby`）→ 放在 [Connection] 里，
 *   建连接时一起造、一起死；
 * - **按需开的**（对局频道，将来每款游戏一个）→ 放进 [Session] 的**槽位表**：
 *   存储在这一个文件里，而"怎么开、怎么关"写在**频道自己的文件夹**里
 *   （比如 `azul/AzulSession.kt` 的 `Session.ensureAzul()` / `closeAzul()`），
 *   于是加一个频道 = 加一个文件，`Session.kt` 一行都不用改。
 *
 * 槽位用**带类型的 key**（[SlotKey]），所以取回来不需要 `as?`：
 *
 * ```kotlin
 * internal object AzulSlot : SlotKey<AzulChannel>()          // 频道自己文件夹里
 * val Session.azul: AzulChannel? get() = slot(AzulSlot)      // 类型自动是 AzulChannel?
 * ```
 *
 * **生命周期约定**（很关键，别绕过）：
 * - 连接只在 [host] / [join] 里建，且每次都是全新的一套 `TcpLink` + 频道
 *   （链路关掉就不能再用，见 `TcpLink.close`）；
 * - [leave] 是唯一收摊入口：清空槽位、关链路，自己切主线程（NsdManager 要 Looper），重复调用安全；
 * - 首页（`RoomsActivity`）每次可见都会调 [leave]，所以"在首页 = 没有连接"是一条硬约束，
 *   异常退出 / 进程重建的残留都会被它兜住。
 *
 * ## 这些约定不是靠 README 守的，是编译器守的
 *
 * | 想偷偷干的事 | 谁挡住了它 |
 * |---|---|
 * | `Session(context)` | 构造器 `private`——只能 [getInstance] |
 * | `TcpLink(...)` | `TcpLink` 的构造器是 `internal`（只有 `:app-net` 能造） |
 * | `link.host/join/close` | 同样是 `internal`——页面拿得到链路，但启动不了、关不掉 |
 * | 各频道的构造器 | `internal`——页面**新建不了**频道 |
 * | `slot(key)` / `setSlot(...)` | `internal`——`:app` 看不到，只能读类型化的扩展属性 |
 *
 * 也就是说 `:app` 里的代码只能"读状态 + 调本文件/扩展函数的公开方法"；
 * 要开连接、要收摊，除了 [host] / [join] / [leave] 没别的口子。
 */
class Session private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 当前连接；`null` = 没连（或者已经收摊）。连上之后不会再变，除非 [leave]。 */
    @Volatile
    var connection: Connection? = null
        private set

    /**
     * 按需开的频道（对局频道、将来的其它游戏频道）都放这儿。
     *
     * 键是 [SlotKey] 单例（不是字符串）：拼不错，也不会和别的频道撞。
     * 值统一按 `Any` 存，但唯一的取法 [slot] 由 key 的类型参数决定返回类型，
     * 所以调用方拿到的永远是正确类型，不需要 `as?`。
     */
    private val slots = HashMap<SlotKey<*>, Any>()

    /**
     * 读一个槽位。**只有 `:app-net` 内部能用**，页面通过各频道文件夹里的扩展属性读。
     *
     * 这个 `as?` 是整个设计里唯一的一次强制转换，而且它不可能失败：
     * key 自己就带着 `T`，而 [setSlot] 只接受 `T`，所以存进去的一定是 `T`。
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> slot(key: SlotKey<T>): T? = slots[key] as T?

    /** 写/清一个槽位（`null` = 清掉）。 */
    internal fun <T : Any> setSlot(key: SlotKey<T>, value: T?) {
        synchronized(slots) {
            if (value == null) slots.remove(key) else slots[key] = value
        }
    }

    private var advertiser: ServiceAdvertiser? = null

    // ── 建连接 ──────────────────────────────────────────────────────────────

    /**
     * 开房：监听端口 → NSD 广播 → 回环连自己 → 进房。
     *
     * **挂起**（内部在 IO 线程阻塞建 socket），成功返回 `null`，失败返回一句给人看的原因。
     */
    suspend fun host(roomName: String, playerName: String, avatar: ByteArray?): String? {
        leave() // 上一轮的残留先收掉（首页进来时本来就没有）
        return try {
            withContext(Dispatchers.IO) {
                val link = TcpLink()
                val port = link.host(0)
                val connection = open(link, LobbyChannel(roomName = roomName, host = true))

                val news = ServiceAdvertiser(appContext, advertiseListener)
                withContext(Dispatchers.Main.immediate) {
                    news.register(connection.lobby.roomName ?: roomName, port)
                }
                advertiser = news

                currentCoroutineContext().ensureActive()
                enter(connection, playerName, avatar)
            }
            null
        } catch (e: CancellationException) {
            leave()
            throw e
        } catch (e: Exception) {
            leave()
            "开房失败：${e.message}"
        }
    }

    /**
     * 加入别人搜到的房间：连上 → 进房。
     *
     * **挂起**，成功返回 `null`，失败返回一句给人看的原因。
     */
    suspend fun join(target: FoundHost, playerName: String, avatar: ByteArray?): String? {
        leave()
        return try {
            withContext(Dispatchers.IO) {
                val link = TcpLink()
                link.join(target.host, target.port)
                val connection = open(link, LobbyChannel(roomName = null, host = false))
                enter(connection, playerName, avatar)
            }
            null
        } catch (e: CancellationException) {
            leave()
            throw e
        } catch (e: Exception) {
            leave()
            "连不上「${target.serviceName}」：${e.message}"
        }
    }

    // ── 收摊 ────────────────────────────────────────────────────────────────

    /**
     * 断开并收掉所有资源：注销 NSD 广播、关链路（各频道会收到 `onLinkClosed`）。
     *
     * 自己切主线程，任意线程 / 重复调用都安全。
     */
    suspend fun leave() {
        withContext(Dispatchers.Main.immediate) {
            runCatching { advertiser?.unregister() }
        }
        advertiser = null
        val current = connection ?: return
        connection = null
        // 按需开的频道（对局频道等）跟着连接一起死：链路一关注册表就空了，别留悬空引用
        synchronized(slots) { slots.clear() }
        current.link.close("离开房间")
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 把新链路登记成"当前连接"，并把常驻频道挂上去（图片 + 资料 + 大厅）。 */
    private fun open(link: TcpLink, lobby: LobbyChannel): Connection {
        // 图片内容寻址、可随时重新拉，所以服务器和客户端共用一个目录，省得两套配额
        val blob = BlobChannel(BlobStore(File(appContext.filesDir, "blobs")))
        val profile = ProfileChannel()
        link.register(blob)
        link.register(profile)
        link.register(lobby)
        return Connection(link, blob, profile, lobby).also { connection = it }
    }

    /**
     * 进房：先把资料报上去（昵称 / 头像），再进房。
     *
     * 顺序有意为之：资料先到，别人拿到名册时就能对上名字；
     * 而头像图片本体走 `blob`，资料里只留内容 id。
     */
    private fun enter(connection: Connection, playerName: String, avatar: ByteArray?) {
        val avatarId = avatar?.let { connection.blob.install(it) }.orEmpty()
        connection.profile.publish(Profile(name = playerName, avatarId = avatarId))
        send(connection, LobbyProtocol.Command.Join)
    }

    private fun send(connection: Connection, command: LobbyProtocol.Command) {
        connection.link.send(LobbyProtocol.NS, LobbyProtocol.encode(command))
    }

    private val advertiseListener = object : ServiceAdvertiser.Listener {
        override fun onFailed(reason: String) {
            connection?.lobby?.attention(reason)
        }
    }

    companion object {

        @Volatile
        private var instance: Session? = null

        /**
         * 进程里唯一的那个会话（`:app` 的 `App.session` 就是它）。
         *
         * 为什么是单例而不是让 App 自己 new：**App 在 `:app` 模块，构造器 `private` 后它造不出来**，
         * 于是"整个进程只有一条链路"这件事由编译器保证，而不是靠文档提醒。
         * 只保存 `applicationContext`，拿 Activity 来调也不会泄漏。
         */
        fun getInstance(context: Context): Session = instance ?: synchronized(this) {
            instance ?: Session(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * 槽位的"带类型的钥匙"：每个按需开的频道在自己文件夹里声明一个单例。
 *
 * ```kotlin
 * internal object AzulSlot : SlotKey<AzulChannel>()
 * ```
 *
 * 好处是**取的时候不用 `as?`**：`slot(AzulSlot)` 的返回类型由 key 的类型参数决定，
 * 传错类型的值直接编译不过。槽位表本身只有 [Session] 能看到（`internal`），
 * `:app` 只能读各频道暴露的类型化扩展属性（`session.azul`）。
 */
internal open class SlotKey<T : Any>
