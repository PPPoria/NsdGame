# 茯苓游戏（NsdGames）

局域网多人小游戏集合。现在跑通的是**游戏准备房间**：搜/开房 → 进房间排座、选游戏、设玩法、
准备/开局 → （对局本身还没写，见下文「下一步」）。

设计目标只有一个：**一个人一个周末能看懂、改得动、测得出**。
所以模块就四个、规则就一个文件、界面就两张布局，没有 DI / EventBus / Repository / UseCase。

> 想接着做／想改房间？先读 **`docs/room-reference.md`**：文件地图、快照协议、
> 线程约定、接一个游戏的步骤、真机验证过的范围，都在那一份里。

```
core/                     纯 Kotlin 内核，零 Android 依赖
  src/main/kotlin/com/poria/net/          ★ 联网通信（纯 JVM，本机就能测）
    Wire.kt               行协议：一条消息 = 一行 UTF-8 + '\n'，单条上限 8KB
    Peer.kt               一条连接：收发 + 一条读线程，回调按序
    PeerListener.kt       连接事件 onOpen/onLine/onClosed——房主 UI 和客人 UI 用同一个接口
    TcpServer.kt          房主：监听端口，广播给所有连接
    Tcp.kt                客户端：Tcp.connect(host, port, listener) 拿到一条 Peer
    CloseCause.kt         LOCAL / REMOTE / ERROR，决定要不要重连
  src/main/kotlin/com/poria/core/game/
    GameCatalog.kt        ★ 游戏目录：能开哪些游戏、几个人玩、有哪些自定义玩法（加游戏只改这里）
  src/main/kotlin/com/poria/core/        时间 / 颜色 / 哈希这类纯工具
  src/test/kotlin/com/poria/
    net/WireTest.kt       行协议单测
    net/TcpTest.kt        127.0.0.1 上真开 socket 的收发/断开/坏包/房主回环测试

app-net/                  局域网层：发现（NSD）+ 房间（快照协议 / 房间规则 / 会话）
  src/main/java/com/poria/netnsd/
    Nsd.kt                服务类型串 + FoundHost
    ServiceAdvertiser.kt  广播"我开了房间"+ TCP 端口
    ServiceDiscovery.kt   搜"谁开了房间" → FoundHost(ip, port)（resolve 单飞队列）
    room/RoomProtocol.kt  ★ 房间协议：命令 + **整份快照**（SNAP）+ ERROR / BYE
    room/Room.kt          ★ 房主侧规则：座位/座次/玩法/准备/踢人/开局（不碰 socket，可直接测）
    room/RoomSession.kt   ★ 把三者缝起来：StateFlow<RoomState> + 一串"发命令"的方法
    room/RoomState.kt     UI 要渲染的东西：本机连接状态 + 服务端快照（RoomSnapshot）
  src/test/java/com/poria/netnsd/room/   协议往返 + 快照裁剪 + 房间规则单测（JVM 直接跑）
  （详见 app-net/README.md）

base/                     Android 基础设施（可复用的，跟游戏无关）
  BaseActivity/BaseFragment/BaseDialog、dp2px、防抖点击、DSManager、ThreadPool
  res/values/dimens.xml  ★ 圆角/间距/字号的唯一来源（corner_s / corner_m / corner_l）
  res/drawable/bg_corner_*  纯色 + 圆角的通用底：房间页的弹窗、拖拽把手都用它

app/                      唯一的应用模块：只有 UI
  src/main/java/com/ppporia/nsdgames/
    ui/RoomsActivity.kt   首页：昵称 + 创建房间 + 搜到的房间列表（RecyclerView）
    ui/RoomActivity.kt    游戏准备房间（房主与客人共用同一个页面）
    ui/RoomAdapter.kt     房间列表适配器
    ui/SeatAdapter.kt     座位条：座位号 = 出手顺序
    ui/MessageAdapter.kt  聊天列表（自己的靠右、别人的靠左带昵称）
    ui/RoomSettingsSheet.kt  底部弹出的房间设置（选游戏 / 牌桌人数 / 玩法开关）
    ui/SeatSheet.kt       点座位弹出的动作面板（入座 / 换座 / 请人离开）
```

依赖方向：`:app` → `:base` → `:core`，以及 `:app` → `:app-net` → `:core`。反向依赖编译不过。

联网的分层（谁该知道什么）：

```
ServiceDiscovery/ServiceAdvertiser  只知道"服务名 + IP + 端口"（Android NSD）
        ↓ FoundHost(host, port)
TcpServer / Tcp.connect             只知道"连上、发一行、收一行"（纯 JVM socket）
        ↓ Peer（房主和客人拿到的是同一个类型）
RoomProtocol / Room                 只知道"谁是谁、谁坐哪、设置是什么、该发给谁"（纯 Kotlin，不碰 socket）
        ↓ RoomSnapshot（整份权威快照）
RoomSession → RoomActivity          只管画和点
```

## 房主自己也是一个客户端（走回环）

房主手机既是 Host（权威状态 + `TcpServer`），也是玩家。**不要让 UI 直接读 Host 的状态**，
否则会出现"两份真相""本机能跑联机就炸""房主权限散在 UI 里"。正确形状：

```
房主手机：
  TcpServer ──┬── 远端客人（LAN socket）
              └── 房主自己（127.0.0.1 回环，同一个 Tcp.connect）
                    ↓ 都是一条普通 Peer
  session / UI（和客人走完全同一条 send → 校验 → 广播 路径）
```

```kotlin
// 房主（RoomSession 内部就是这么干的）
val server = TcpServer(0, session).start()
val me = Tcp.connect("127.0.0.1", server.localPort, hostPlayer)  // 和客人同一个函数
advertise(server.localPort)

// 客人
val host = Tcp.connect(ip, port, guestSession)
```

"房主特权"（改设置、踢人、开局）是**服务端侧的策略**：`Room` 记住哪个 playerId 是房主
（回环连上来的那条），收到命令时校验发送方，而不是让房主 UI 绕过连接去改状态。
房主退出 = 服务端消失（房间解散）；权威转移明确不做。

**房主那条连接为什么不走内存管道**：做过，又撤了。省下的是每方向几 KB 内核缓冲和几个
系统调用，代价是多一条链路（管道阻塞语义、关闭语义、背压上限）要跟 socket 对齐并长期维护。
回合制消息量下这点开销可以忽略，**一条代码路径更值钱**。`TcpTest` 里有一条
`房主用回环连自己的服务端，和远端客人没有区别` 盯着这件事。

## 房间：一切以服务端快照为准

旧版聊天室让客户端拿 `JOINED` / `LEFT` / `LOG` 一串增量事件**自己攒花名册**——
攒错一条就永远两边不一致（排序、重连、丢包都会踩）。现在改成了**快照协议**：

```
客户端 → 服务端（只是"请求"，能不能改由 Room 说了算）
  JOIN|<playerId>|<昵称>   SAY|<内容>     READY|<0|1>
  SEAT|<座位>              SWAP|<a>|<b>   SIZE|<人数>
  GAME|<游戏id>            OPT|<玩法id>|<0|1>  KICK|<playerId>  START

服务端 → 客户端
  SNAP|<整份快照>          ERROR|<原因>（那条命令没生效）
  BYE|<原因>（这条连接被关掉：满员 / 被踢 / 房间解散）
```

三条设计决定，改协议前先想清楚：

1. **客户端没有"我的花名册"**。它只有"最近一次收到的快照"，UI 直接照着画。
   发命令不改本地状态——等服务端把新快照推回来（含发起者自己）才动界面。
2. **`playerId` 由客户端自己生成**（`UUID` 前 16 位），服务端认可后就是身份。
   这样客户端不用等服务端"告诉我我是谁"就知道快照里哪一行是自己；
   座位是可变的（房主排座次），身份不可变。
3. **房主是服务端认的**：`127.0.0.1` 回环连上来的那条连接，`Room` 给它打 `host` 标记。
   `RoomState.iAmHost` 也只看快照里的 `hostId`——UI 灰按钮只是顺手，校验在服务端。

快照里带的东西：房名、房主 id、阶段、游戏 id、牌桌人数、`startHint`（服务端算好的"现在还开不了局"的原因，
空串 = 能开）、玩法开关、按座位排序的玩家（id / 座位 / 昵称 / 是否房主 / 是否准备）、聊天记录。
**`startHint` 由服务端算**，客户端拿它显示在「开始游戏」按钮上，不重复判一遍规则。

快照是一行 `键=值`，值里出现的 `% | ; , =` 和换行都会被转义（`RoomProtocol.esc/unesc`），
所以昵称、房名、聊天内容里带任何分隔符都不会切错。单行有 8KB 上限，**超预算时从最老的聊天
开始丢**——人和设置一条不少。

上限（`RoomProtocol`）：昵称 12、内容 300、房名 24、playerId 16、错误 80 字符；快照目标 6KB。

## 座位 / 玩法 / 游戏目录

- **座位号就是出手顺序**。牌桌人数由游戏决定（花砖物语 2–4 人，默认 4 个座位），
  空座位也画出来；房主把两个人一换就是排座次（`SWAP`）。
- **准备 / 开局**：客人点「我准备好了」，服务端算出 `startHint`（人不够 → "还差 N 人"；有人没准备 →
  "等 X、Y 准备"；都齐了 → 空串），房主按钮上直接显示这个原因；按「开始游戏」时服务端再校验一遍。
  改人数、换游戏、开关玩法会清空所有人的准备状态（同一条规则）。
- **游戏目录在 `:core` 的 `GameCatalog`**：加一款游戏 = 加一条 `GameInfo`（人数范围 + 玩法开关
  的默认值）。首页的"今天能开"、设置面板的"选游戏 / 选人数 / 玩法开关"都照着它渲染，不用改 UI 代码。

## 下一步：把对局接进来

`START` 现在会过完"人数够、都准备好了"的校验，然后返回一句
「花砖物语的对局还在施工」。接游戏的步骤在 `docs/room-reference.md` 第 7 节，
一句话版：在 `Room.start()` 里把 `return` 换成 `phase = PLAYING` + 广播，
再按 `RoomProtocol` 的同一套"命令 → 规则 → 快照"形状加对局命令。

## 跑起来

```bash
./gradlew :core:test          # 行协议 + 真 socket 测试，几秒跑完，不需要模拟器
./gradlew :app-net:test       # 房间协议 + 快照裁剪 + 房间规则 + 真 socket 收发（JVM 单测）
./gradlew :app:assembleDebug  # 打包 APK
```

## 想改东西时改哪个文件

| 想改什么 | 改这里 |
|---|---|
| 消息怎么切（分隔符、长度上限） | `core/.../net/Wire.kt` |
| 谁连谁、连接/广播/断开 | `core/.../net/TcpServer.kt`、`Tcp.kt`、`Peer.kt` |
| 房主自己怎么连自己 | `Tcp.connect("127.0.0.1", server.localPort, hostPlayer)`，没有特殊代码 |
| 加游戏 / 改人数范围 / 加玩法开关 | `core/.../game/GameCatalog.kt` |
| 房间协议（加一条命令 / 快照加字段） | `app-net/.../room/RoomProtocol.kt` |
| 房间规则（谁能改什么、广播给谁） | `app-net/.../room/Room.kt` |
| 开房/加入的流程 | `app-net/.../room/RoomSession.kt` |
| 房间怎么被局域网发现 | `app-net/.../ServiceAdvertiser.kt`、`ServiceDiscovery.kt` |
| 首页长什么样 / 列表点了之后干什么 | `app/.../ui/RoomsActivity.kt` |
| 房间页长什么样 / 座位条 / 设置面板 | `app/.../ui/RoomActivity.kt`、`SeatAdapter.kt`、`RoomSettingsSheet.kt`、`SeatSheet.kt` |
| 圆角 / 间距 / 字号 | `base/res/values/dimens.xml`（**别在 app 里写魔法值**） |

## 架构硬约束（以后让 AI 写代码时把这段贴给它）

单人项目，边界由人定，AI 只填实现。**以下每一条都是"不许"**：

1. 只有 `:core` / `:base` / `:app-net` / `:app` 四个模块，**禁止新增模块**。
   分模块的唯一理由永远是"依赖不同"：`:core` 不碰 Android（所以 `:core:test` 秒跑），
   `:app-net` 必须碰 Android（`NsdManager`），`:base` 是界面基建。说不清跟谁"依赖不同"就别拆。
2. **禁止新增依赖**。现在只有 AndroidX 基础库、Material、kotlinx-coroutines；
   socket 用 JDK 自带的 `java.net`，网络发现用系统的 `android.net.nsd`，都不引库。
3. 禁止 Hilt / Koin / Dagger、RxJava、EventBus、Room、Navigation。
4. 禁止"只有一个实现的接口"、禁止 UseCase / Repository / Mapper 分层、禁止自己写 DI 容器。
   （`TcpServer.Listener` / `ServiceDiscovery.Listener` 这类回调接口不算：它们是给调用方覆盖的，不是拿来注入的。）
5. 规则判定只能写在 `:core`。UI 或 session 里出现"这张牌能不能出""现在能不能开局"的判断就是 bug——
   应该问 `GameCatalog` / 让服务端的 `Room` 给出 `startHint` 或拒绝原因。
6. **客户端不许有自己的房间状态**。花名册、座位、设置、聊天记录都只能来自服务端推来的快照；
   客户端"先改本地再等服务端确认"是 bug（两份真相）。
7. 新文件先按 200 行估一遍，超了就想想是不是在造框架。
8. 每个功能一次做完再做下一个，不要预留"以后可能要"的扩展点。

审查时只问五句话：这个抽象有第二个实现吗？这个类能一句话说清职责吗？新增依赖了吗？
规则判定有没有散到 UI / 网络里？客户端是不是又在偷偷存状态？

## 联网分三层，别越界

- `:core` 的 `com.poria.net` **只搬字符串**：不认识游戏，不认识 Android，**也不引协程**
  （阻塞写 = 天然背压；换 channel 就得回答"队列满了丢哪条消息"）。消息体是 JSON 还是
  `PLAY|red|5` 都由上层决定，这一层只管"一行一条、收发、断开"。
- **一条链路，一个类型**：远端客人、房主自己（`127.0.0.1` 回环）都是 `Tcp.connect` 拿到的
  `Peer`，`PeerListener` 也是同一个接口——**会话代码里不允许出现"这是房主所以走另一条路"的分支**。
- `:app-net` 是**唯一能同时碰协程和 Android API** 的联网模块：NSD 发现（resolve 单飞队列用
  `Channel` + 挂起 `for` 循环），以及房间这一层（协议 / 房间规则 / 会话）。`Room` 刻意
  不碰 socket（只收成员给的 `send` / `close` 回调），所以房间规则能在 JVM 上单测；
  `RoomSession` 才把它们缝起来。
- `:app` **只有 UI**：不解析协议、不判断谁能改设置（按钮灰不灰跟着快照走），
  `RoomActivity` 只做 `state.collect { render }` + 发命令。
- 线程：`TcpServer.start` / `Tcp.connect` 是**阻塞**的，别在主线程调（`RoomSession` 已放进协程）；
  `ServiceAdvertiser` / `ServiceDiscovery` 的方法**必须**在主线程调（NsdManager 要 Looper），
  `RoomSession.close()` 也是（它要注销广播）。`Peer` 的回调在那条连接的读线程上按序到达，
  NSD 的回调已经替你抛回主线程，UI 侧收的是 `StateFlow`，所以哪条线程都不用手动切。

## 当前刻意简化的地方

- 界面：无动画、无音效；聊天记录不落盘（退出房间即清空）；牌桌最多 6 座，花砖物语 2–4 人。
- 转屏靠 `android:configChanges` 不重开（房间页尤其重要：重建会 `close()` 掉房间）。
- 联网：只有"行协议 + socket + 房主回环 + NSD 发现 + 房间快照"这几块，
  **断线重连、离线消息、房主权威转移都没写**；房主退出 = 房间解散。
- 对局规则还没写：`START` 只做校验然后给一句提示（`GameInfo.available = false`）。
- 进程被杀后再回到房间页，会按 Intent 重新开一个同名房间（历史消息不保留）。

## 版本管理约定

依赖坐标和版本只写在 `gradle/libs.versions.toml`，模块里不允许出现带版本号的依赖。
插件版本在根 `build.gradle.kts` 用 `apply false` 声明。
