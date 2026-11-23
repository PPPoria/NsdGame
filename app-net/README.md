# app-net —— 局域网层（发现 + 链路 + 房间）

三块东西：

1. **找到对方**：`ServiceAdvertiser` / `ServiceDiscovery`（NSD/mDNS）。
2. **对上话**：`link/` 下的 `TcpLink`——一条链路管 socket、peer 身份和**按频道路由**。
3. **挂在上面的频道**：`blob/BlobChannel`（谁有谁发）、`lobby/`（大厅规则 / 协议 / 频道）、`profile/`（资料）。
4. **持住连接**：顶层的 `Session`——进程里唯一那条 `TcpLink` 就在它手上（`App.session`）。

不搬字节（那在 `:core`），不碰界面（那在 `:app`）。

## 链路：TcpLink

```
房主：val port = link.host(0)      // TcpServer + 自己走 127.0.0.1 回环连上来；端口交给上层做 NSD
客人：link.join(ip, port)          // Tcp.connect
      ↓ 连上就报到（_link 的 hello），房主回 welcome
link.playerId = "p1"               // 身份是房主分的；各频道的 onLinked 也被叫一遍

线上：FRAME|{"v":1,"ns":"lobby","to":null,"from":null,"p":{...}}
```

- **身份由房主分配**：客户端不自己编 id——`hello` 里不带身份，房主收到就排一个号
  （`p1` / `p2` / …）回 `welcome`。这样"谁是谁"只有一份来源，`Room` 和对局频道拿到的
  永远是链路认可过的 id；`host` / `join` 返回时 `playerId` 已经有值。
- **TcpLink 只认 `ns`**，按频道把 `p` 原样交给注册的 `TcpHandler`；帧里没有"命令/推送"字段——
  方向由"从哪条连接收到"决定：服务端侧收到 → `onCommand(player, payload)`，
  客户端侧收到 → `onEvent(payload, from)`。
- `ns = "_link"` 是链路保留频道：`hello` / `welcome` / `ping` / `pong` / `bye`，
  Handler 抢不到这个名字。**昵称、头像这类个人信息不归链路管**，交给上层的 `profile` 频道。
- **房主标记只给"带着凭据的回环连接"**：`host()` 现场生成一份随机凭据塞进 `hello`，
  服务端比对上了才打 `isHost`。凭据不上别人的手，所以客户端自称房主没用。
- **心跳归链路**：默认 10 秒 ping 一次、25 秒没收到任何帧就断开那条连接（局域网里手机直接消失
  不会有 FIN，只能靠应用层心跳发现）。房主断开 = 房间解散。
- **发送不在链路锁里做**：先把 peer 快照拍出来，锁外写 socket，一个卡死的对端堵不住整条链路。
- 链路**不碰 NSD**（那是 Android），所以 `TcpLink` 能在 JVM 上直接跑单测。

### 中转（星型点对点）

带 `to` 的帧请房主转发，`from` 由房主盖章：

```
客人发：  FRAME|{"v":1,"ns":"chat","to":"guest-2","p":{...}}
房主转：  FRAME|{"v":1,"ns":"chat","from":"guest-1","p":{...}}
guest-2 的 Handler 收到：onEvent(payload, from = "guest-1")
```

- 房主侧 `LinkOut.sendTo` 直接投递；客人侧自动走中转（`TcpLink.sendTo` 也是同一套语义）。
- 客户端发来的 `from` **一律不信**，房主转发时用自己记下的发送者覆盖。
- 目标不存在时静默丢弃（尽力而为）；权威数据仍走房主，中转只适合聊天/表情/观战这类
  "不需要裁决"的数据。

### 上层怎么写（链路不管这些）

链路只保证"谁发的、发给谁、连没连上"，剩下的都是 Handler：

| 频道 | 干什么 | 怎么用中转 |
|---|---|---|
| `profile` | 昵称、头像 id（`avatarId: String?`） | 报到时各自广播一次自己的资料；缺什么来问谁 |
| `blob` | 图片本体（分块） | **谁有谁发**：缺图的一方 `sendTo(ownerId, "blob", {id})`，拥有者分块 `sendTo(requester, …)` 推回来；房主只转发、不存图。内容是 sha1 寻址的，接收方验过 hash 才用 |
| `lobby` | 座位 / 设置 / 准备 / 开局 | 权威数据，走房主 |
| `azul`… | 各游戏的对局状态 | 权威数据，走房主 |

这样 `TcpLink` 不需要认识任何一个业务：加频道 = 加一个 `TcpHandler`，链路一行不改。
（想自己加一个频道？照着 **`../docs/handler-channel-guide.md`** 抄，那份是完整步骤。）

| 类 | 干什么 | 测得了吗 |
|---|---|---|
| `link/Frame` | `Frame` + `_link` 小协议 + `LinkCodec`（编解码/版本） | ✅ |
| `link/TcpHandler` | `PlayerRef` / `TcpHandler` / `LinkOut`（频道的收发契约） | — |
| `link/TcpLink` | socket、peer 身份、路由、心跳、中转、关闭 | ✅ 真 socket |
| `blob/BlobChannel` | `ns = "blob"` 的常驻频道：**谁有谁发**、3KB 分块、下载进度/完成/失败 | ✅ 真 socket |
| `blob/BlobStore` | 内容寻址的图片仓库（房主和客户端共用）：sha1、分块拼装、hash 校验、LRU | ✅ |
| `blob/BlobFrames` | `blob` 频道的负载（want / chunk / missing / failed）+ 分块读文件 | — |
| `blob/BlobLimits` | chunk 大小、单图上限、缓存配额（房主/客户端共用一份） | — |
| `blob/BlobCommands` | 图片 API（`Session.imageFile/requestImage/images/imageProgress`）：只管"按 id + 拥有者拉"，不认识头像/花纹 | — |

## 图片：`BlobChannel`（谁有谁发，房主只中转）

```
缺图的一方：  out.sendTo(ownerId, {"type":"want","id":"<sha1>"})        ← 房主侧直投 / 客人侧自动中转
拥有者：      out.sendTo(缺图的人, {"type":"chunk","id":…,"index":0,"total":14,"size":41000,"data":"<base64 3KB>"})
收齐：        BlobStore 验 sha1 → progress 里那条消失 → ready 里出现 id
要不到：      {"type":"missing"} / {"type":"failed"} → failed 里出现原因
```

- **id = sha1(内容)**：接收方拼完自己验一遍，所以"谁发都行、房主不可信也传不坏"，
  房主不用替别人存图；`builtin:` 开头的素材打包在 APK 里，永远不过网。
- **进度对外暴露**：`channel.progress: StateFlow<Map<String, BlobProgress>>`，
  `BlobProgress` 里有 `received / total` 字节数和 `percent`；收齐进 `ready`、要不到进 `failed`。
- **发送串行**：同一 `(收件人, id)` 只允许一条流（交错会打乱块序，`BlobStore` 整份放弃），
  发送在频道自己的 IO 协程里跑，不占链路读线程。
- 频道自己的 API（`Session` 上的扩展，见 `blob/BlobCommands.kt`）：
  `session.imageFile(id)`、`session.requestImage(id, ownerId)`、`session.images`（能画的 id 集合）、
  `session.imageProgress`（id → 字节数/百分比）；`BlobChannel` 自己的
  `install(bytes)` / `ready` / `failed` 留给上传和事件订阅。

> **全进程只有一个 `BlobChannel`**（`Session` 持有），后续页面**订阅**它，不要自己 new 一个：
> 一来链路上一 `ns` 只能有一个 handler，二来磁盘仓库是"一份索引 + 一份 `$id.part` 临时文件"，
> 两个实例指同一个目录会互相踩坏（块序乱 → sha1 不过 → 整份放弃），还会各拉一遍同一张图。
> 谁拥有这张图是**页面的事**（房间页看 `players[].avatar`，对局页看自己的快照），
> 页面只把 `(id, ownerId)` 递进来。

> 房间里的头像走的就是这个频道，**没有"房间版 blob"**：快照只带 `avatar`（内容 id），
> 房间页自己从 `players[].avatar == id` 找到拥有者，然后 `session.requestImage(id, owner)`；
> 房主只中转。旧的"房主存图 + `BLOB` 帧"那套已经删掉了。

## 为什么单独一个模块

`NsdManager` 属于 `android.net.nsd`，是 Android API。把它放进 `:core` 会让那个
"零 Android 依赖、`./gradlew :core:test` 秒跑"的内核变成 Android 模块。
真正搬字节的 socket 代码在 `:core`（纯 JVM，有集成测试），这里负责**怎么找到对方、以及房间里说什么**——
所以它是唯一能同时用协程和 Android API 的联网模块。

```
:app  ──┬─→ :base ──→ :core
        ├─→ :app-net ──┬─→ :core
        │              └─→ :game-core      ← 大厅校验人数/玩法用
        └─→ :game-core（纯叶子：谁的依赖都不加）
```

## 发现：两个类 + 两个数据

| 类 | 干什么 | 谁用 |
|---|---|---|
| `ServiceAdvertiser` | 广播"我开了房间"（注册 NSD 服务，带上 TCP 端口） | 房主 |
| `ServiceDiscovery` | 搜"谁开了房间"，回调里给出 IP + 端口 | 想加别人房间的人 |
| `FoundHost` | 一条搜索结果：`serviceName` / `host` / `port` | 上面两个的公共数据 |
| `NSD_SERVICE_TYPE` | 服务类型串 `_ppporiagames._tcp.`，两边必须一致 | 两边 |

## 大厅：`lobby` 频道（规则 / 协议 / 频道）

| 类 | 干什么 | 测得了吗 |
|---|---|---|
| `lobby/Lobby` | 服务器侧规则：分座位、排座次、改人数、换游戏、玩法开关、准备、踢人、开局校验。**纯状态机**：不碰 socket、不广播，改没改东西看 `version` | ✅ JVM 单测 |
| `lobby/LobbyProtocol` | `lobby` 频道的负载格式：命令（join/say/ready/seat/swap/size/game/option/kick/start）、事件（snap/rejected）、快照序列化与超预算裁剪 | ✅ JVM 单测 |
| `lobby/LobbyChannel` | **一个 `TcpHandler` 两半**：服务端半边（只有服务器有 `Lobby`）收命令改状态、按 `version` 变没变推快照；客户端半边把快照变成 `LobbyState` | ✅ 真 socket |
| `lobby/LobbyCommands` | 大厅动作的扩展函数：`Session.say/setReady/…`（**只管命令**）。命令是协议的事，不是会话对象的事 | — |
| `lobby/LobbyState` | UI 要渲染的东西：本机连接状态（CONNECTING/ONLINE/CLOSED）+ 服务器快照（`LobbySnapshot`，里面的人**只有 id + 座位**）；`mergedWith` 把链路状态、可画的图叠上去 | — |
| `Session`（顶层，不在 `lobby/`） | **进程级连接持有者**：建/关 `TcpLink`，持有当前那一条 `Connection`（link + blob + profile + lobby）和 NSD 广播 | ❌ 要真机 |

```kotlin
// 进程里唯一的一个（Session 构造器是 private，只能这么拿；`:app` 的 App.session 就是它的包装）
val session = Session.getInstance(BaseApp.context)

// 服务器
val error = session.host("小明的房间", "小明", avatarBytes)   // suspend，null = 成功
session.connection?.lobby?.state?.collect { … }             // 整份快照在里面
session.connection?.profile?.profiles?.collect { … }        // 昵称/签名/头像 id 在这里
session.say("开一局？")
session.setReady(true)
session.leave()                      // 唯一收摊入口，自己切主线程

// 客户端
val error = session.join(foundHost, "小红", avatarBytes)
```

## 资料：`profile` 频道（昵称 / 签名 / 头像 id）

| 类 | 干什么 | 测得了吗 |
|---|---|---|
| `profile/ProfileFrames` | 负载（`put` / `want` / `info`）+ 领域类型 `Profile(name, signature, avatarId)` + 尺寸上限与清洗 | ✅ JVM 单测 |
| `profile/ProfileChannel` | **一个 `TcpHandler` 两半**：服务器侧把 `put` 盖 id 后广播、把 `want` 转给本人（**自己不存资料**）；客户端侧维护 `profiles`（已知资料）和 `pending`（问过谁），`publish` 推、`want` / `sync` 拉 | ✅ 真 socket |

为什么要单独一个频道：房间快照只该管"身份/座位/聊天/设置"。以前昵称和头像 id 塞在快照里，
**头像还没装好就进房了，别人只能等下一份快照才知道有头像**——而下一份快照可能要等到有人说句话。
拆出来之后，资料什么时候就绪什么时候走。

**推和拉是两件事，两条都要有**：

| | 谁发起 | 消息 | 语义 |
|---|---|---|---|
| **推** | 资料的主人 | `put` → 服务器广播 `info` | "我的资料变了，大家都更新一下" |
| **拉** | 缺资料的人 | `want(playerId)` →（中转）→ 本人回 `info` | "我想看看你的资料" |

- **拉的时候对方可以选择不答**（不想公开就不回，问的人一直显示占位）——这就是"匿名模式"，
  不需要额外消息类型；`pending` 保证同一个 id 不会问第二遍（名册怎么变都不会变请求风暴）；
- 两条路的回答都是 `info`，所以接收侧只有一条路径；
- 权威归**本人**：`put` / `want` 里都没有 id，服务器用"谁发的"盖章，谁也改不了别人的资料；
- 服务器**不存资料**，只做"把 `put` 变广播、把 `want` 转给本人"；
- 头像只有 id，图片本体走 `blob`（见下面的图）。

### 边界是 `internal`，不是文档

`:app` 拿不到"造/关"的能力，越界直接编译失败（不是靠 review 发现）：

| 想偷偷干的事 | 谁挡住 |
|---|---|
| `Session(context)` | 构造器 `private`，只能 `getInstance(context)` |
| `TcpLink(...)` | 构造器 `internal`（`:app-net` 之外不可见） |
| `link.host/join/close` | `internal`——页面拿得到链路，但开不了、关不掉 |
| `BlobChannel(...)` / `LobbyChannel(...)` / `ProfileChannel(...)` / `BlobStore(...)` / `Lobby(...)` | 构造器 `internal`——页面**新建不了**频道 |
| `Connection(...)` | 构造器 `internal`——伪造不了连接 |

`internal` 的边界是**模块**：`:app-net` 内部任何文件都能造（测试也要造），`:app` / `:base` / `:core` 一律看不到。
页面能做的只有：读 `state` / `playerId`、`register` 自己的频道、发帧、调 `Session` 的公开方法和扩展函数。

**为什么不是"页面持有会话"**：socket 和 NSD 广播比任何一个页面都活得久（房间页进对局页时
它们必须还在）。所以 `Session` 挂在 `App` 的伴生对象上（`App.session`，全进程一个），页面只"用而不持有"：
VM 把 `lobby.state` / `profile.profiles` / `link.state` / `blob.available` 几条流拼成 UI 状态，Activity 只渲染。
**换页不传链路**，下一个页面的 VM 从 `App.session.connection` 拿；它自己的频道（比如 `azul`）
自己 `register` / `unregister`，但绝不关链路。

## 协议：帧 + ns，快照是唯一数据通道

链路层把每条消息包成 `Frame`（`link/Frame.kt`），每个频道只管它自己的 payload：

```
客户端 → 服务器   Frame(ns="lobby", p={"type":"join"})
                 Frame(ns="lobby", p={"type":"say","text":"开一局？"})
                 Frame(ns="lobby", p={"type":"start"})

服务器 → 成员     Frame(ns="lobby", p={"type":"snap","lobby":{…}})            ← 只发给房间成员
服务器 → 某人     Frame(ns="lobby", from="<服务器>", p={"type":"rejected","reason":"…"})

客户端 → 服务器   Frame(ns="profile", p={"type":"put","profile":{…}})
服务器 → 所有人   Frame(ns="profile", p={"type":"info","id":"p2","profile":{…}})
```

四条重要的约定：

1. **快照推给所有房间成员，包括发起者**。客户端不做本地回显/推演，名册、座次、设置
   永远只有服务器这一份真相；"拒绝原因"只回给发命令的那个人。
2. **`playerId` 由服务器分配**（`_link` 的 hello → welcome）。客户端不自己编 id，
   座位可变、身份不变；`TcpLink.playerId` 在 `host`/`join` 返回时就有值，
   `LobbyChannel` 通过 `onLinked` 拿到同一份，所以"快照里哪一行是我"不用等。
3. **负载是 JSON**（kotlinx.serialization）：命令/事件/快照都是 `@Serializable` 数据类，
   加字段只改数据类，`ignoreUnknownKeys` 让新旧版本不会互相炸。
4. **单行上限 8KB**（`Wire`），快照目标 6KB——**超了先丢最老的聊天**，人和设置一条不少。
   昵称/头像/签名不在快照里（`profile` 频道），所以快照膨胀的来源只剩聊天。

"谁是服务器自己的那个人"是**链路认出来的**（带着本机凭据的那条回环连接），`Lobby` 里带 `host` 标记，
改设置/踢人/开局都按它校验。客户端自称"我是房主"没有用。

## 加入：搜索 + 连接

```kotlin
val discovery = ServiceDiscovery(context, object : ServiceDiscovery.Listener {
    override fun onFound(room: FoundHost) = roomList.add(room)          // 直接用，别自己解析 IP
    override fun onLost(serviceName: String) = roomList.removeAll { it.serviceName == serviceName }
})

discovery.start()   // 主线程；想重扫只能 stop() 再 start()

// 用户点了某个房间之后
val error = session.join(room, "小红", avatarBytes)   // suspend，null = 成功
```

房主和客人拿到的是**同一个 `Connection` 形状**（`link` + `blob` + `lobby`），UI 只有一份代码。

## 已经替你处理掉的坑

- **resolve 只能单飞**：`NsdManager` 同时只允许一个 `resolveService`，第二个直接抛
  `IllegalArgumentException("listener already in use")`。这里不写状态机：`onServiceFound`
  把服务塞进 `Channel`，一个收集协程 `for (info in inbox) { resolve(info) }` 一条一条解析
  （`resolve` 是 `suspendCancellableCoroutine` 包出来的挂起函数，循环天然停在原地等它）。
  于是"单飞"由结构化并发保证，而不是由 `resolving: Boolean` 保证——`stop()` 只需要
  `job.cancel()`，不用清队列、也不用复位标志位（漏复位就会永久卡死，这正是手写状态机的坑）。
- **取消要能真取消**：取消时走 `invokeOnCancellation` → API 34 上补一次
  `stopServiceResolution`，否则下一次搜索会撞上"listener already in use"。
- **回调线程不确定**：系统回调线程 → `Handler(mainLooper)`；主线程路径直接调。
  `onFound` / `onLost` / `onError` 一律在主线程，下游可以放心改 UI。
- **重复发现**：同一个服务会被反复 `onServiceFound`，这里按服务名去重，只有地址真变了才再回调。
- **IPv4 优先**：双栈设备会一起给 IPv4 和链路本地 IPv6，优先取 IPv4（IPv6 在局域网里常常连不通）。
- **start 失败也要 stop**：`onStartDiscoveryFailed` 里补了 `stopServiceDiscovery`，否则系统里那次
  注册留着，之后再也起不来。
- **系统会改名**：同名房间会被改成 `茯苓的牌桌 (2)`，以 `onRegistered` 给的名字为准。
- **权限**：`INTERNET` 已在模块清单里声明，manifest merger 会合进 `:app`。

## 依赖

本模块用 `kotlinx-coroutines-android`（本来就在允许列表里，`:app` 也早就带着它）：一处是发现层的
resolve 收集协程，一处是 `Session.host/join/leave` 里建连接、等身份、切主线程注销 NSD
（调用方在自己的 `viewModelScope` 里 `launch` 就行，模块内不留后台 scope）。
**`:core` 的 socket 层刻意不引协程**：
那里 `send()` 是阻塞写，慢对端自然形成背压；换成 channel 就得回答"队列满了丢哪条消息"，
丢一条聊天/出牌消息是没法诊断的 bug。

另外多了一个 `kotlinx-serialization-json`（+ 对应编译器插件），给 `link/` 的帧、`lobby/` 的
命令/快照、`profile/` 的资料、`blob/` 的分块负载用：都是 `@Serializable` 数据类，整份 JSON 直接上线，
不用手写字段拼接和转义。它只出现在这个模块，`:core` / `:base` / `:app` 都没有它。

## 必须记住的约束

1. `ServiceAdvertiser` / `ServiceDiscovery` **只能在主线程调用**（`Session.leave()` 已经自己切了）；
   反过来 `TcpLink.host` / `TcpLink.join` 是**阻塞**的，别在主线程调（`Session` 已经放进 IO 协程）。
2. 一个进程里不要同时开两个实例广播同一个服务（`FAILURE_ALREADY_ACTIVE`）；
   也**不要同时留两条链路**——`TcpLink` 关掉就不能再用，`Session` 每次都建全新的一套。
3. `ServiceDiscovery` / `Session` 没单元测试（`NsdManager` 是 final 的 Android 类，JVM 侧测不了）。
   所以这两处的原则是**把状态压到最少**：`ServiceDiscovery` 只剩 3 个字段
   （`discoveryListener` / `inbox` / `job`，外加主线程独占的 `known`），全都能用 `stopInternal()`
   一次收干净；`Session` 只有 `connection` + `advertiser` 两个字段，建/关各一条路。
4. 能测的都往 `Lobby` / `LobbyProtocol` / `LobbyChannel` / `ProfileChannel` / `BlobChannel` / `TcpLink` 里放：
   它们不碰 socket 的测试部分也不碰 Android，`./gradlew :app-net:test` 秒跑（`LobbyOverLinkTest`
   用的是真 socket + 回环，也不需要模拟器）。加功能时先想"能不能变成这几个类里的纯逻辑"。
5. **客户端不存房间状态**：`Connection` 里没有名册、没有座位表，只有频道里的"最近一份快照"
   （`LobbyChannel.state`）和"最近一份资料"（`ProfileChannel.profiles`）。要展示新东西，
   先问一句"它会不会改变谁能做什么"：会 → 加进 `LobbySnapshot`（服务器算好再推）；
   不会 → 另开频道/另加字段，别塞进快照。
