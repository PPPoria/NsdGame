# 为你的 Activity 设计 Handler / Channel

> 读者：要给新页面（对局页、观战页、图鉴页…）加一套联网能力的人。
> 结论先给：**一个页面一套频道 = 一个协议文件 + 一个 Handler 类 + VM 里两行注册/注销**，
> 链路（`TcpLink`）和 `Session` 一行都不用改。
>
> 完整的参照实现就是房间那一套：`app-net/.../lobby/LobbyProtocol.kt` +
> `lobby/LobbyChannel.kt` + `lobby/LobbyCommands.kt`，资料频道是 `profile/`。这份指南本质上是"照着抄一遍"。

## 0. 先说清三个角色（别把"服务器"和"房主"当一个东西）

这三个词在这份文档里是**分开用**的，混起来后面的协议就讲不清了：

| 角色 | 是什么 | 代码里是谁 |
|---|---|---|
| **服务器**（server，权威） | 跑规则、持有权威状态、给所有人推快照的那个进程。它**不是**"房主手机"，只是"调了 `link.host()` 的那个 `TcpLink`" | `TcpLink.host()` 起的 `TcpServer` + 拿 `Room`/规则引擎的那个 Handler 的**服务端侧** |
| **房主**（host player） | 一个**玩家**。今天就是他手机上也跑着服务器，所以他是"服务器的第一个客户端" | 那条 `127.0.0.1` 回环连上来的连接，`PlayerRef.isHost == true` |
| **客户端**（client / guest） | 其他玩家 | 各自的 `TcpLink.join()` + 同一个 Handler 的**客户端侧** |

于是方向永远是：

```
客户端 ──command／payload──→ 服务器           （onCommand）
服务器 ──event／快照──────→ 每个客户端        （onEvent）
```

- **命令只发给服务器**，不是"发给房主"；房主只是因为自己也是服务器所在机器上的一个客户端，
  所以他下命令时也走"客户端 → 服务器"（经回环）。
- **事件和快照只由服务器发**；写着 `from=<某人>` 的帧是服务器**转发**的（`sendTo` 中转），
  `from=null` 是服务器自己推的。客户端之间从不直接说话。
- `PlayerRef.isHost` 的意思是"**这条连接是服务器自己的那条客户端连接**"，
  不是"这个人是服务器"。`Room.hostOnly()` 认的就是它——也就是说"改设置/踢人/开局"的权限
  今天等于"服务器自己的那个人"的权限。

**能不能把服务器和房主拆开？** 可以，而且链路/协议不用动：写个无人值守的 app
（只调 `Session.getInstance(ctx).host(...)` 当服务器，没有 UI），房主留在别人手机上。
真正要改的只有一处——今天的"谁是房主"是 `_link` 的 `hello` 里那份凭据（`hostClaim`）
认出来的**服务器自己的回环连接**，服务器一和房主分家，就没有这条连接了，
得重新回答"谁有权改设置"（新的授权模型）。
**这件事没做**，所以本文里"房主"仍然等于"服务器自己那条客户端"。

## 0.1 动手前先答三个问题

| 问题 | 房间（`lobby`） | 对局（假设叫 `azul`） |
|---|---|---|
| **状态归谁** | **服务器**：`Room` 是唯一权威（今天它就在房主手机上） | **服务器**：`:game-core` 的规则引擎是权威 |
| **谁收得到** | 房间成员整份快照；拒绝只回本人 | 全员整份局面；手牌用 `broadcastEach` 一人一份 |
| **什么时候死** | 回首页（`session.leave()` 关链路） | 退对局页：`unregister("azul")`，**链路不动** |

这三条答不上来就别写代码——尤其是第三条：**页面走 ≠ 连接断**。链路是进程的（`App.session`），
页面只是订阅者。

## 1. 起个 `ns`

`ns` 是频道的身份证，出现在每一帧的 `FRAME|{"v":1,"ns":"…","p":…}` 里。

- 一个 `ns` 只能注册一个 Handler（`TcpLink.register` 第二次返回 `false`）；
- `_link` 是链路保留的（报到/心跳/告别），不许占；
- 用业务名，别用页面名：`lobby` / `blob` / `azul`，而不是 `RoomActivity`；
- **两个方向共用一个 `ns`**：服务端半边和客户端半边写在同一个类里（§4 解释为什么）。

## 2. 设计协议：sealed interface

一个频道只需要三个东西：**命令**（客户端 → 服务器）、**事件**（服务器 → 客户端）、
**快照**（权威状态的载体，其实是事件里的一种）。全部用 `sealed interface` + `@Serializable`：

```kotlin
package com.poria.netnsd.azul

import com.poria.netnsd.link.LinkCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object AzulProtocol {

    /** 频道名：`link.register(AzulChannel(...))` 用。 */
    const val NS = "azul"

    /** 协议版本；加不兼容字段才动它。快照里带着它，认不出就整份丢。 */
    const val VERSION = 1

    @Serializable
    sealed interface Command {

        @Serializable
        @SerialName("place")
        data class Place(val row: Int, val factory: Int, val color: Int) : Command

        @Serializable
        @SerialName("next")
        data object Next : Command
    }

    @Serializable
    sealed interface Event {

        @Serializable
        @SerialName("snap")
        data class Snapshot(val game: AzulSnapshot) : Event

        @Serializable
        @SerialName("rejected")
        data class Rejected(val reason: String) : Event
    }

    private val json get() = LinkCodec.json // 和链路共用一份 Json 配置

    fun encode(command: Command): JsonElement = json.encodeToJsonElement<Command>(command)

    /** 坏负载返回 `null`：**坏包不该让谁的进程崩**，也不该断链路。 */
    fun decodeCommand(payload: JsonElement?): Command? = payload?.let {
        runCatching { json.decodeFromJsonElement<Command>(it) }.getOrNull()
    }

    fun encode(event: Event): JsonElement = json.encodeToJsonElement<Event>(event)

    fun decodeEvent(payload: JsonElement?): Event? = payload?.let {
        runCatching { json.decodeFromJsonElement<Event>(it) }.getOrNull()
    }

    fun rejected(reason: String): JsonElement = encode(Event.Rejected(reason))
}

/** 权威局面：客户端只渲染它，不做本地推演。 */
@Serializable
data class AzulSnapshot(
    val version: Int = AzulProtocol.VERSION,
    val round: Int,
    val turnId: String,
    // …工厂区/中央区/各家手牌
)
```

**为什么是 sealed interface**（这五条就是理由）：

1. `when (command)` 是**穷举**的——加一条命令，忘了处理的地方**编译报错**，不用靠记性；
2. `@SerialName` 就是线上的 `type` 字段，一个注解同时管住编解码和可读性；
3. 工具函数只有一处（`encode` / `decode`），不会出现"编码按 A 格式、解码按 B 格式"；
4. 未知/坏负载天然返回 `null`，一个 `?: return` 就挡住了；
5. 测试超好写：`decode(encode(x)) == x`，纯 JVM。

**加字段/改字段的代价**：加字段 → 给默认值即可（`ignoreUnknownKeys` 让老客户端忽略它）；
删改字段 → 升 `VERSION`，老客户端看到版本不认识就整份丢弃（宁可不动，也不渲染半份状态）。

## 3. 认识 `TcpHandler`

`app-net/.../link/TcpHandler.kt` 定义了频道的全部契约。**所有回调都在"收到消息那条连接的读线程"上执行**，
不同连接之间可能并发——所以 Handler 自己负责线程安全（和 `Room` 用一把锁是同一个取舍）。

| 成员 | 什么时候被调用 | 你在这里做什么 |
|---|---|---|
| `val ns: String` | — | 频道名，注册表里唯一 |
| `attach(out: LinkOut)` | 注册时一次，**早于任何消息** | 存下发件箱 `out` |
| `onLinked(localId)` | 服务器给本机分了身份之后（注册晚于报到时会立刻补一次） | 记下"我是谁"（`myId`） |
| `onPeerJoined(player)` | 有人报到（服务器自己那条回环连接也算一次） | **服务器侧**：给新人补一份当前快照 |
| `onCommand(player, payload)` | **服务器侧**收到 | 解码命令 → 校验 → 改状态 → 推快照 |
| `onEvent(payload, from)` | **客户端侧**收到 | 解码事件 → 更新 `StateFlow` |
| `onPeerLeft(playerId)` | 那个人走了（退出/被踢/心跳超时） | **服务器侧**：把他移出对局并推快照 |
| `onLinkClosed(reason)` | 本机链路关了 | 取消自己的 scope，状态落 `CLOSED` + 原因 |

**方向由"从哪条连接收到"决定，帧里没有"命令/推送"字段**：
同一份 payload，在服务器侧的 Handler 走 `onCommand`，在客户端侧的 Handler 走 `onEvent`。
`from` 是"经**服务器**转发来的发送者"，服务器自己广播/直投的是 `null`
（`from` 一律由服务器盖章，客户端伪造无效）。

不需要的回调就别重写：纯客户端没有服务器半边，`onCommand` 空着就行（真收到命令说明对端搞错了，
直接忽略）；只做客户端的频道连 `onPeerJoined` 都不用管。

**放到设备上看更清楚**（这是最容易搞混的地方）：

| 设备 | 那台机子上的 `TcpLink` | 同一个 Handler 对象会收到 |
|---|---|---|
| **房主手机** | 服务器 + **它自己的客户端**（同一个 `TcpLink` 对象，回环连着自己） | `onCommand`（客人发来的命令）**和** `onEvent`（自己广播出去的事件、自己发出的快照） |
| **客人手机** | 只有客户端 | 只有 `onEvent` |

所以"一个类写两半"不是设计洁癖：房主手机上那个 Handler 客观上两边都得收（§4）。

### 发件箱 `LinkOut`

| 方法 | 语义 | 用在哪 |
|---|---|---|
| `sendTo(id, payload)` | 服务器侧**直投**；客户端侧**自动经服务器中转**。目标不在 → `false`，静默 | 要图、拒绝原因、私聊类 |
| `broadcast(payload)` | 发给所有已报到的人（**含服务器自己的那条回环客户端**） | 全员一样的状态 |
| `broadcastEach { id -> payload? }` | 每个玩家一份；返回 `null` 表示"这个人不给" | 手牌、私有信息、按人裁剪 |
| `sendCommand(payload)` | 客户端方向：发给服务器（等于 `link.send(ns, p)`） | 命令 |
| `disconnect(id, reason)` | 把某个玩家踢下线（只有服务器侧有效） | 违规/被请出去 |

### 三条禁止（不是礼貌，是编译器/链路会挡）

- **别想自己造链路**：`TcpLink` 的构造器和 `host/join/close` 都是 `internal`，
  `:app` 里写 `TcpLink()` 或 `link.close()` 直接编译不过。连接只有一个入口：`Session.host/join/leave`。
- **别自己做心跳/重连**：心跳在链路里（10 秒 ping、25 秒超时），业务频道不掺和。
- **别在回调里干重活**：回调跑在读线程上，你在这里睡一秒，这条连接就堵一秒。
  块传输那类活丢到频道自己的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 里（`BlobChannel` 就是样板）。

## 4. 新建你的 Handler

**一个类写两半**——这是 `TcpLink` 的一条硬规矩逼出来的：

> 房主手机上同时跑着**两个角色**：服务器（拿着 `Room`/引擎，收命令）和它自己的客户端
> （那条回环连接，收快照）。两者挂在同一个 `ns` 上，而一个 `ns` 只能注册一个 Handler，
> 所以两半必须是同一个类，各走各的回调。
>
> 如果将来服务器和房主分家（§0 说的那种），服务器进程里只会有服务端半边，
> 房主手机上只剩客户端半边——**类不变，只是另一半没人用**。

```kotlin
class AzulChannel(
    /** 只有**服务器**传非 null（服务端半边：规则引擎）；纯客户端传 null。 */
    private val round: Round?,
) : TcpHandler {

    override val ns: String = AzulProtocol.NS

    /** 服务端半边：命令处理 + 广播必须串行，否则快照会乱序。 */
    private val lock = Any()

    @Volatile
    private var out: LinkOut? = null

    private val _state = MutableStateFlow(AzulState())
    /** 客户端半边：UI 只会读这一条流。 */
    val state: StateFlow<AzulState> = _state.asStateFlow()

    override fun attach(out: LinkOut) {
        this.out = out
    }

    override fun onLinked(localId: String) {
        _state.update { it.copy(myId = localId) }
    }

    // ── 服务端半边（只有服务器有 round）──────────────────────────────────────

    override fun onPeerJoined(player: PlayerRef) {
        val snapshot = synchronized(lock) { round?.snapshot() } ?: return
        // 新人（含服务器自己那条回环客户端）一进来就给他一份当前局面
        out?.sendTo(player.id, AzulProtocol.encode(AzulProtocol.Event.Snapshot(snapshot)))
    }

    override fun onCommand(player: PlayerRef, payload: JsonElement?) {
        val round = round ?: return // 纯客户端收到命令 = 对端搞错了，忽略
        val command = AzulProtocol.decodeCommand(payload) ?: return

        synchronized(lock) {
            val rejection = when (command) {
                is AzulProtocol.Command.Place -> round.place(player.id, command.row, command.factory, command.color)
                is AzulProtocol.Command.Next -> round.next(player.id)
            }
            if (rejection == null) publish() else reject(player.id, rejection)
        }
    }

    override fun onPeerLeft(playerId: String) {
        synchronized(lock) {
            if (round?.left(playerId) == true) publish()
        }
    }

    // ── 客户端半边（两边都有）────────────────────────────────────────────────

    override fun onEvent(payload: JsonElement?, from: String?) {
        when (val event = AzulProtocol.decodeEvent(payload) ?: return) {
            is AzulProtocol.Event.Snapshot -> {
                // 版本不认识的整份丢掉：宁可不动，也不渲染半份状态
                if (event.game.version != AzulProtocol.VERSION) return
                _state.update { it.copy(game = event.game, phase = Phase.Playing, note = null) }
            }

            is AzulProtocol.Event.Rejected -> _state.update { it.copy(note = event.reason) }
        }
    }

    override fun onLinkClosed(reason: String?) {
        _state.update { it.copy(phase = Phase.Closed, note = it.note ?: reason) }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 整份推给**所有对局内的人**；手牌这类用 broadcastEach 一人裁剪一份。 */
    private fun publish() {
        val engine = round ?: return
        val payload = AzulProtocol.encode(AzulProtocol.Event.Snapshot(engine.snapshot()))
        out?.broadcastEach { id -> if (engine.contains(id)) payload else null }
    }

    private fun reject(playerId: String, reason: String) {
        out?.sendTo(playerId, AzulProtocol.rejected(reason))
    }
}
```

要点：

- 上面代码里的 `Round`（规则引擎）、`AzulState` / `Phase`（UI 侧状态）是**示意类型**，
  照着自己的业务起名即可；对应房间那一套就是 `Lobby` 和 `LobbyState`/`SessionPhase`。
- **服务端半边只改规则、只推快照**，从不写 `_state`；**客户端半边只写 `_state`**。
  这样"谁是权威"在代码结构上就一眼看得出来，不会出现两份真相。
- **规则本身不碰网络**：`Round`（或 `:game-core` 的引擎）只管"这步合不合法"，返回 `null`/拒绝原因；
  `AzulChannel` 负责"该发给谁"。这条线越了就会出现"客户端和服务端各有一套判定"的经典 bug。
- `synchronized(lock)` 包住"改状态 → 编码 → 发"三步：只包规则会让两份快照乱序到达。
- 拒绝只回给**发命令的那个人**（`sendTo`），不要广播。
- 只推给对局内的成员（`broadcastEach` + 成员判断），链路里报到过但没进对局的人不该看到局面。

### 4.1 页面侧：注册与注销

频道类住在 `:app-net`（协议和规则都在那边），页面只做两件事：

```kotlin
class AzulVM : ViewModel() {

    private val session = App.session
    private val link = session.connection?.link
    private val channel = AzulChannel(round = null) // 纯客户端没有权威；服务器侧传引擎

    /** 链路状态（没连上时用一份空的，UI 不用判空）。 */
    private val linkState = link?.state ?: MutableStateFlow(TcpLink.LinkState())

    /** 拼装层：频道状态 + 链路状态 + 能画的图，和 RoomVM 一样的写法。 */
    val state: StateFlow<AzulUiState> = combine(
        channel.state,
        linkState,
        session.images,
    ) { azul, linkNow, images -> azul.toUi(linkNow, images) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = channel.state.value.toUi(linkState.value, session.images.value),
        )

    init {
        check(link?.register(channel) == true) { "${AzulProtocol.NS} 频道已被占用" }
    }

    override fun onCleared() {
        // 只注销自己：链路是进程的，退了页面还要回大厅
        link?.unregister(AzulProtocol.NS)
    }

    fun place(row: Int, factory: Int, color: Int) = session.place(row, factory, color)
}
```

- `register` 返回 `false` 只有两种原因：`ns` 被占了，或者用了保留的 `_link`。
- 如果这个频道是在链路建好之后才注册的，`onLinked` 会立刻补一次（不用自己去问"我是谁"）。
- **`onCleared` 里绝不 `close` 链路**（也确实调不到：`close` 是 `internal`）。

### 4.2 命令写成扩展函数

和 `lobby/LobbyCommands.kt` 一个套路：命令是协议的事，不要往 `Session` 上加方法。

```kotlin
fun Session.place(row: Int, factory: Int, color: Int) = sendAzul(AzulProtocol.Command.Place(row, factory, color))

fun Session.nextRound() = sendAzul(AzulProtocol.Command.Next)

private fun Session.sendAzul(command: AzulProtocol.Command) {
    val link = connection?.link ?: return
    link.send(AzulProtocol.NS, AzulProtocol.encode(command))
}
```

## 5. 处理收发的 payload

**编码**：只用 `LinkCodec.json`（`ignoreUnknownKeys = true`、`encodeDefaults = true`、
`explicitNulls = false`）。别自己 `Json { }` 建第二个实例——两边配置不一致就是对端解不出来。

**解码**：永远 `runCatching` → `null`，然后 `?: return`。坏负载的正确处理是"忽略这一条"，
不是"断开连接"、也不是"回一个错误给对端"（对方可能是老版本）。

**大小**：一行上限 8KB（`Wire.MAX_LINE_BYTES`）。快照按 6KB 目标设计，
**超了先丢最不重要的尾巴**，权威部分一条不少——房间的做法是丢最老的聊天，
见 `LobbyProtocol.encodeSnapshot` 里那个 while 循环：

```kotlin
var messages = snapshot.messages
while (true) {
    val payload = encode(Event.Snapshot(snapshot.copy(messages = messages)))
    val line = LinkCodec.encode(Frame(ns = NS, p = payload))
    if (messages.size <= 1 || line.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) return payload
    messages = messages.subList(1, messages.size)   // 从最老的开始丢
}
```

**大对象不进 payload**：图片/纹理/存档一律走 `blob`（附录 A）。8KB 是"一条消息"的上限，
不是"传输容量"。

**快照只装"权威"**：判据是一句话——**这条数据会不会改变"谁能做什么"？**
会（身份、座位、回合、准备、设置）→ 进快照，服务器算好再推；
不会（昵称、头像、签名、装饰）→ **另开频道**。
这条不是洁癖，是踩过的坑：资料塞在快照里，它什么时候就绪就由别人说了算——
房间原有的例子是"头像没装好就进房，别人得等下一份快照才知道"。
现在它们是 `profile` 频道：**推**（`put` → 广播 `info`）管"我的资料变了"，
**拉**（`want` → 本人回 `info`，也可以不回）管"我刚进来，把不认识的人问一遍"。
房间快照只留 id。

**私有信息用 `broadcastEach`**：别先广播再让客户端"别显示"——那是把秘密交给对方保管。

**校验在服务器**：长度上限、数值范围、是不是你的回合、你在这局里吗——都在服务器侧判，
客户端只负责显示服务端给的结论（`startHint`、`rejected`）。客户端自己也再判一遍，
就是两份规则，迟早对不上。

**别把"结果"塞回 payload**：命令的结果就是下一份快照。也不要放时间戳/随机数进 payload
（对局要可重放、可测）。

## 6. Activity 只做三件事

```kotlin
override suspend fun bindUi() = vm.state.collect { render(it) }   // 1. 渲染

binding.buttonPlace.setOnClickListenerWithClickAnimation {         // 2. 点击 → VM
    vm.place(row = 2, factory = 3, color = 0)
}
```

3. 把"怎么画"交给资源层：图片 id → 文件 → Bitmap（附录 A），Activity 里不存业务数据。

三个老规矩照样适用：**不存状态**（渲染完就丢，最多留一个"最近一次状态"给弹面板时读一眼）、
**不解析协议**（`Frame`、`JsonElement` 都不该出现在 `:app`）、**不判规则**（按钮灰不灰跟着快照走）。

### 6.1 一个真踩过的坑：`stateIn` 会按 equals 去重

```
combine(几个流) → map { 拍平成 UiState } → stateIn(...)      ← StateFlow
```

StateFlow **只在值不相等时才发射**。所以只要"渲染要用到的某样东西"没进 UI 状态，
它在后台变化时那次更新就会被**静默吞掉**，界面要等下一次别的变化才刷新。

房间页的原版 bug 就是这个形状：`blob.available` 参与了 combine，但 `RoomUiState` 里没有它——
座位只带 `avatarId`，图到没到是 Activity 里临时查的。于是"第一次下完头像"这条更新
产出的状态和上一份**完全相等**，`stateIn` 不发射、`render` 不跑，头像要等下一个事件才出现
（图已经在缓存里时反而正常，因为第一次渲染就拿得到——这就是"只复现一次"的原因）。

两条规矩：

- **UI 状态里包含渲染依赖的每一样东西**。文件路径、进度值都算——拿不准就把"解析结果"放进
  状态（房间的做法是 `SeatUi.avatar: File?`，由 VM 用 `session.imageFile(id)` 查好），
  而不是让 Activity 去后台查表；
- 反过来说，`stateIn` 的去重是**好事**：它能挡住无意义的重绘。别用 `MutableStateFlow` 手推、
  也别为了绕过它加"随机 tick"——那是把 bug 藏起来。

## 7. 怎么测（不需要模拟器）

三档，按性价比从高到低：

1. **纯逻辑**：规则、编解码、裁剪。对着 `snapshot()` / 返回值断言，不碰 socket。
   模板：`lobby/LobbyTest.kt`、`lobby/LobbyProtocolTest.kt`。
2. **真 socket 两端**：同一个 JVM 里开两个 `TcpLink`（`:app-net` 里能造）：
   一个 `host()`（服务器 + 它自己那条回环客户端），一个 `join()`（客户端）。
   服务器侧注册带引擎的频道，客户端侧注册同一个类的另一半，然后断言两边的 `state`
   收敛到同一份快照——`LobbyOverLinkTest` 里的"报到之后两边拿到同一份快照"就是这个形状。
   模板：`lobby/LobbyOverLinkTest.kt`、`profile/ProfileChannelTest.kt`、`blob/BlobChannelTest.kt`。
3. **手工**：设备自带 `nc`，直接发帧，`docs/room-reference.md` 第 9 节有脚本。

```bash
./gradlew :app-net:test    # 几十秒跑完，不用模拟器
```

## 附录 A：简单使用 blob 推拉图片

图片不是你的频道的事——全进程只有一个 `blob` 频道（`Session` 持有），你只是用它。
API 在 `app-net/.../blob/BlobCommands.kt`。

### A.1 自己产生一张图（用户选的图、生成的纹理）

```kotlin
val id = session.connection?.blob?.install(bytes) ?: return   // 返回 sha1(内容)，失败返回 null
// 把 id 通过你自己的频道广播出去（图片本体不过快照，只传 id）
// 假设协议里有个 data class Texture(val id: String) : Event
session.connection?.link?.send(AzulProtocol.NS, AzulProtocol.encode(Event.Texture(id)))
```

`install` 只落盘 + verify，不联网。别人想拿这张图，会按 id 来找你。

### A.2 拉一张图

```kotlin
// 1) 谁拥有这张图？—— 业务数据说了算（不是 blob 频道的事）。
//    房间用的是 profile 频道：Map<id, Profile> 的 key 就是本人，头像 id 在 value 里。
val owner = profiles.entries.firstOrNull { it.value.avatarId == id }?.key ?: return

// 2) 要
session.requestImage(id, owner)

// 3) 进度 + 可用性（VM 里 combine 进 UI 状态）
val images = session.images          // StateFlow<Set<String>>：哪些已经能画了
val progress = session.imageProgress // StateFlow<Map<String, BlobProgress>>：percent / received / total

// 4) 取文件（没到就返回 null，先画占位）
val file = session.imageFile(id)
```

`requestImage` 自带去重：本机已有、已经在拉、`builtin:` 素材都直接返回 `false`；
30 秒要不到会把占位撤掉（下次重画再要一次）。

### A.3 必须知道的五条

1. **谁有谁发，服务器只中转**：服务器不替别人存图，所以"向拥有者要"永远成立。
2. **id = sha1(内容)**：接收方拼完自己验一遍，所以发送方是谁都行，传坏了整份丢弃。
3. **`builtin:` 开头的 id 永远不过网**：打包在 APK 里的素材（默认纹理），UI 直接从资源加载。
4. **别新建 `BlobChannel`**：构造器是 `internal`，`:app` 里编译不过；一个进程只有一份
   （磁盘仓库是"一份索引 + 一份 `.part` 临时文件"，两份会互相踩坏）。
5. **别把图片塞进你的 payload**：8KB 上限 + 快照每次变化都重发，塞进去等于爆。

尺寸约束：一块 3KB（base64 后 ~4.2KB）、单图 ≤4MB、本机缓存 64MB LRU，都在 `BlobLimits`。

## 附录 B：新频道检查清单

协议

- [ ] `ns` 唯一、不是 `_link`、用业务名
- [ ] `VERSION` + 快照里带 `version`；不认识就整份丢
- [ ] `Command` / `Event` 都是 `sealed interface`，每个 case 一个 `@SerialName`
- [ ] 所有 `decode` 都 `runCatching` → `null`
- [ ] 快照有字节预算，超了只丢"尾巴"，权威部分不动

频道

- [ ] 两半写在同一个类（一个 `ns` 一个 Handler）
- [ ] 服务端半边不写 `_state`，客户端半边只写 `_state`
- [ ] 命令处理 + 发布在 `synchronized` 里
- [ ] 私有信息走 `broadcastEach`
- [ ] 拒绝只 `sendTo` 发命令的人
- [ ] 重活丢到自己的 IO scope，回调里不阻塞
- [ ] `onLinkClosed` 里取消自己的 scope + 落 `CLOSED`

页面

- [ ] 频道类在 `:app-net`，页面只 `register` / `unregister`
- [ ] `onCleared` 只 `unregister`，**不**碰链路（`close` 也调不到）
- [ ] Activity 只渲染 + 转点击，不解析协议、不存业务数据
- [ ] 命令写成 `Session` 的扩展函数

测试

- [ ] 纯逻辑测试（规则 + 编解码 + 裁剪）
- [ ] 真 socket 两端测试：两边 `state` 收敛到同一份快照
- [ ] 坏负载/未知命令不会断链路（喂一个 `{"type":"???"}` 试试）
