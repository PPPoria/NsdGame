# 茯苓游戏（NsdGames）

单人 Android 小游戏集合。**第一个游戏是单机 UNO**（1 个真人 + 3 个电脑）。

设计目标只有一个：**一个人一个周末能看懂、改得动、测得出**。
所以模块就四个、规则就一个文件、界面就一张画布，没有 DI / EventBus / Repository / UseCase。

```
core/                     纯 Kotlin 内核，零 Android 依赖
  src/main/kotlin/com/poria/uno/
    Card.kt               UnoColor / UnoKind / UnoCard + 108 张牌的整副牌
    UnoState.kt           一局游戏的完整状态（不可变）+ isPlayable
    UnoAction.kt          Play / Draw / Pass + IllegalMove
    UnoEngine.kt          ★ 全部规则：出牌合法性、跳过/反转/罚牌、摸牌、洗牌重发、胜负
    UnoAi.kt              电脑玩家（贪心，不搜索）
  src/main/kotlin/com/poria/net/          ★ 联网通信（纯 JVM，本机就能测）
    Wire.kt               行协议：一条消息 = 一行 UTF-8 + '\n'，单条上限 8KB
    Peer.kt               一条连接：收发 + 一条读线程，回调按序
    PeerListener.kt       连接事件 onOpen/onLine/onClosed——房主 UI 和客人 UI 用同一个接口
    TcpServer.kt          房主：监听端口，广播给所有连接
    Tcp.kt                客户端：Tcp.connect(host, port, listener) 拿到一条 Peer
    CloseCause.kt         LOCAL / REMOTE / ERROR，决定要不要重连
  src/test/kotlin/com/poria/
    net/WireTest.kt       行协议单测
    net/TcpTest.kt        127.0.0.1 上真开 socket 的收发/断开/坏包/房主回环测试

app-net/                  局域网发现（NSD/mDNS），只有两个类
  src/main/java/com/poria/netnsd/
    ServiceAdvertiser.kt  广播"我开了牌局"+ TCP 端口
    ServiceDiscovery.kt   搜"谁开了牌局" → FoundHost(ip, port)
    Nsd.kt                服务类型串 + FoundHost
    （详见 app-net/README.md：resolve 单飞、去重、IPv4 优先这些坑都在那儿）

base/                     Android 基础设施（可复用的，跟游戏无关）
  BaseActivity/BaseFragment/BaseDialog、dp2px、防抖点击、DSManager、ThreadPool

app/                      唯一的应用模块
  src/main/java/com/ppporia/nsdgames/
    ui/MenuActivity.kt    首页
    ui/UnoActivity.kt     对局页：把状态画出来、把点击变成动作、显示错误提示
    ui/UnoTableView.kt    ★ 牌桌自绘（Canvas + onTouchEvent，不用 RecyclerView）
    session/GameSession.kt 状态持有 + 转发动作 + 跑电脑；StateFlow 给 UI 订阅
```

依赖方向：`:app` → `:base` → `:core`，以及 `:app` → `:app-net` → `:core`。反向依赖编译不过。

联网的分层（谁该知道什么）：

```
ServiceDiscovery/ServiceAdvertiser  只知道"服务名 + IP + 端口"（Android NSD）
        ↓ FoundHost(host, port)
TcpServer / Tcp.connect             只知道"连上、发一行、收一行"（纯 JVM socket）
        ↓ Peer（房主和客人拿到的是同一个类型）
Session / UnoEngine                 只知道"规则"（纯 Kotlin）
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
// 房主
val server = TcpServer(0, session).start()
val me = Tcp.connect("127.0.0.1", server.localPort, hostPlayer)  // 和客人同一个函数
advertise(server.localPort)

// 客人
val host = Tcp.connect(ip, port, guestSession)
```

"房主特权"（开局、踢人）是**服务端侧的策略**：Host 记住每个连接的角色，收到命令时校验发送方，
而不是让房主 UI 绕过连接去改状态。房主退出 = 服务端消失（房间解散）；权威转移明确不做。

**房主那条连接为什么不走内存管道**：做过，又撤了。省下的是每方向几 KB 内核缓冲和几个
系统调用，代价是多一条链路（管道阻塞语义、关闭语义、背压上限）要跟 socket 对齐并长期维护。
回合制消息量下这点开销可以忽略，**一条代码路径更值钱**。所以房主出牌和客人出牌在服务端
眼里完全一样，`TcpTest` 里有一条 `房主用回环连自己的服务端，和远端客人没有区别` 盯着这件事。

## 跑起来

```bash
./gradlew :core:test          # 行协议 + 真 socket 测试，几秒跑完，不需要模拟器
./gradlew :app:assembleDebug  # 打包 APK
```

## 想改东西时改哪个文件

| 想改什么 | 改这里 |
|---|---|
| 规则（能不能出、跳过反转、罚几张） | `core/.../UnoEngine.kt` |
| 状态里有哪些字段 | `core/.../UnoState.kt` |
| 电脑怎么出牌 | `core/.../UnoAi.kt` |
| 消息怎么切（分隔符、长度上限） | `core/.../net/Wire.kt` |
| 谁连谁、连接/广播/断开 | `core/.../net/TcpServer.kt`、`Tcp.kt`、`Peer.kt` |
| 房主自己怎么连自己 | `Tcp.connect("127.0.0.1", server.localPort, hostPlayer)`，没有特殊代码 |
| 房间怎么被局域网发现 | `app-net/.../ServiceAdvertiser.kt`、`ServiceDiscovery.kt` |
| 牌桌长什么样 | `app/.../ui/UnoTableView.kt` |
| 点了牌之后干什么 | `app/.../ui/UnoActivity.kt` |
| 本地打还是联网打 | `app/.../session/GameSession.kt` |

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
5. 规则判定只能写在 `:core`。UI 或 session 里出现"这张牌能不能出"的判断就是 bug——
   应该问 `UnoState.isPlayable` 或让 `UnoEngine.reduce` 抛 `IllegalMove`。
6. 新文件先按 200 行估一遍，超了就想想是不是在造框架。
7. 每个功能一次做完再做下一个，不要预留"以后可能要"的扩展点。

审查时只问四句话：这个抽象有第二个实现吗？这个类能一句话说清职责吗？新增依赖了吗？
规则判定有没有散到 UI / 网络里？

## 联网分三层，别越界

- `:core` 的 `com.poria.net` **只搬字符串**：不认识 UNO，不认识 Android，**也不引协程**
  （阻塞写 = 天然背压；换 channel 就得回答"队列满了丢哪条消息"）。消息体是 JSON 还是
  `PLAY|red|5` 都由上层决定，这一层只管"一行一条、收发、断开"。
- **一条链路，一个类型**：远端客人、房主自己（`127.0.0.1` 回环）都是 `Tcp.connect` 拿到的
  `Peer`，`PeerListener` 也是同一个接口——**会话代码里不允许出现"这是房主所以走另一条路"的分支**，
  那是这套设计唯一要守住的东西。省一个链路（内存管道）是刻意的：少一套要跟 socket 对齐的语义。
- `:app-net` **只回答"对方 IP 是多少"**：不建连接、不发消息、不碰 UI。它是唯一用协程的
  联网模块——用 `Channel` + 挂起 `for` 循环把"一次只解析一个服务"交给结构化并发，
  省掉排队队列和状态标志位。
- 线程：`TcpServer.start` / `Tcp.connect` 是**阻塞**的，别在主线程调；
  `ServiceAdvertiser` / `ServiceDiscovery` 的方法**必须**在主线程调（NsdManager 要 Looper）。
  `Peer` 的回调在那条连接的读线程上按序到达，NSD 的回调已经替你抛回主线程。

## 当前刻意简化的地方

- UNO 规则：不喊"UNO"罚牌、不做 +4 质疑、不计分、不打多局。
- 界面：自绘无动画、无音效、手牌不拖拽（点一下出牌）。
- 转屏靠 `android:configChanges` 不重开；真要横竖屏两套布局时再把状态搬进 ViewModel。
- 联网：只有"行协议 + socket + 房主回环 + NSD 发现"这几块地基，**房间协议（谁当庄、
  断线重连、悔棋仲裁、房主权威转移）没写**。动手之前先看 `app-net/README.md` 里
  "已经替你处理掉的坑"。

## 版本管理约定

依赖坐标和版本只写在 `gradle/libs.versions.toml`，模块里不允许出现带版本号的依赖。
插件版本在根 `build.gradle.kts` 用 `apply false` 声明。
