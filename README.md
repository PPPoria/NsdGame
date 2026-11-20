# 茯苓游戏（NsdGames）

单人 Android 小游戏集合。现在跑通的是**局域网聊天**（页面两个：搜/开房、聊天室），
UNO 游戏源码暂时不在仓库里（等你决定还回不回来）。

设计目标只有一个：**一个人一个周末能看懂、改得动、测得出**。
所以模块就四个、规则就一个文件、界面就两张布局，没有 DI / EventBus / Repository / UseCase。

> 想把聊天样例改成游戏房间？先读 **`docs/chat-reference.md`**：文件地图、协议、
> 线程约定、改造成对局的步骤、真机验证过的范围，都在那一份里。

```
core/                     纯 Kotlin 内核，零 Android 依赖
  src/main/kotlin/com/poria/net/          ★ 联网通信（纯 JVM，本机就能测）
    Wire.kt               行协议：一条消息 = 一行 UTF-8 + '\n'，单条上限 8KB
    Peer.kt               一条连接：收发 + 一条读线程，回调按序
    PeerListener.kt       连接事件 onOpen/onLine/onClosed——房主 UI 和客人 UI 用同一个接口
    TcpServer.kt          房主：监听端口，广播给所有连接
    Tcp.kt                客户端：Tcp.connect(host, port, listener) 拿到一条 Peer
    CloseCause.kt         LOCAL / REMOTE / ERROR，决定要不要重连
  src/main/kotlin/com/poria/core/        时间 / 颜色 / 哈希这类纯工具
  src/test/kotlin/com/poria/
    net/WireTest.kt       行协议单测
    net/TcpTest.kt        127.0.0.1 上真开 socket 的收发/断开/坏包/房主回环测试

app-net/                  局域网层：发现（NSD）+ 房间（协议/规则/会话），能用协程和 Android API
  src/main/java/com/poria/netnsd/
    Nsd.kt                服务类型串 + FoundHost
    ServiceAdvertiser.kt  广播"我开了房间"+ TCP 端口
    ServiceDiscovery.kt   搜"谁开了房间" → FoundHost(ip, port)（resolve 单飞队列）
    chat/ChatProtocol.kt  ★ 聊天协议：JOIN / SAY / WELCOME / JOINED / LEFT / LOG / ERROR
    chat/ChatRoom.kt      ★ 房主侧规则：座位分配、重名拒绝、广播给谁（不碰 socket，可直接测）
    chat/ChatSession.kt   ★ 把三者缝起来：StateFlow<ChatState> + say() + close()
    chat/ChatState.kt     UI 要渲染的不可变状态
  src/test/java/com/poria/netnsd/chat/   协议往返 + 房间规则单测（JVM 直接跑）
  （详见 app-net/README.md）

base/                     Android 基础设施（可复用的，跟游戏无关）
  BaseActivity/BaseFragment/BaseDialog、dp2px、防抖点击、DSManager、ThreadPool

app/                      唯一的应用模块：只有 UI
  src/main/java/com/ppporia/nsdgames/
    ui/RoomsActivity.kt   首页：昵称 + 创建房间 + 搜到的房间列表（RecyclerView）
    ui/ChatActivity.kt    聊天室：标题/花名册/消息列表/输入框（房主与客人共用同一个页面）
    ui/RoomAdapter.kt     房间列表适配器
    ui/MessageAdapter.kt  消息适配器（自己的靠右、别人的靠左带昵称）
```

依赖方向：`:app` → `:base` → `:core`，以及 `:app` → `:app-net` → `:core`。反向依赖编译不过。

依赖方向：`:app` → `:base` → `:core`，以及 `:app` → `:app-net` → `:core`。反向依赖编译不过。

联网的分层（谁该知道什么）：

```
ServiceDiscovery/ServiceAdvertiser  只知道"服务名 + IP + 端口"（Android NSD）
        ↓ FoundHost(host, port)
TcpServer / Tcp.connect             只知道"连上、发一行、收一行"（纯 JVM socket）
        ↓ Peer（房主和客人拿到的是同一个类型）
ChatProtocol / ChatRoom             只知道"谁是谁、这句话发给谁"（纯 Kotlin，不碰 socket）
        ↓ ChatState（StateFlow）
ChatSession → ChatActivity          只管画和点
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
// 房主（ChatSession 内部就是这么干的）
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
回合制消息量下这点开销可以忽略，**一条代码路径更值钱**。所以房主的消息和客人的消息在服务端
眼里完全一样，`TcpTest` 里有一条 `房主用回环连自己的服务端，和远端客人没有区别` 盯着这件事。

## 聊天室的协议与房间

协议在 `app-net/.../chat/ChatProtocol.kt`：`类型|字段|字段`，**自由文本永远是最后一个字段**，
所以内容里带 `|` 不会被切错；**只认座位不认昵称**（昵称可能重、可能改，座位稳定）。

```
客户端 → 服务端    JOIN|<昵称>              SAY|<内容>
服务端 → 客户端    WELCOME|<座位>|<房名>     JOINED|<座位>|<昵称>
                   LEFT|<座位>               LOG|<座位>|<内容>
                   ERROR|<原因>
```

- `ChatRoom`（房主侧规则）：分配座位、拒绝重名/满员、把 LOG 广播给**所有人（含发送者）**——
  客户端不做本地回显，消息顺序只有一份真相。
- `ChatSession`：把 `TcpServer`、NSD 广播、回环客户端、`ChatRoom` 缝在一起，对外只有
  `state: StateFlow<ChatState>`、`say(text)`、`close()`。房主和客人**同一个入口形状**，
  UI 拿不到"我是房主"的特殊通道。

## 跑起来

```bash
./gradlew :core:test          # 行协议 + 真 socket 测试，几秒跑完，不需要模拟器
./gradlew :app-net:test       # 聊天协议 + 房间规则（JVM 单测）
./gradlew :app:assembleDebug  # 打包 APK
```

## 想改东西时改哪个文件

| 想改什么 | 改这里 |
|---|---|
| 消息怎么切（分隔符、长度上限） | `core/.../net/Wire.kt` |
| 谁连谁、连接/广播/断开 | `core/.../net/TcpServer.kt`、`Tcp.kt`、`Peer.kt` |
| 房主自己怎么连自己 | `Tcp.connect("127.0.0.1", server.localPort, hostPlayer)`，没有特殊代码 |
| 聊天协议（加一条命令/事件） | `app-net/.../chat/ChatProtocol.kt` |
| 房间规则（谁能进、广播给谁） | `app-net/.../chat/ChatRoom.kt` |
| 开房/加入的流程 | `app-net/.../chat/ChatSession.kt` |
| 房间怎么被局域网发现 | `app-net/.../ServiceAdvertiser.kt`、`ServiceDiscovery.kt` |
| 首页长什么样 / 列表点了之后干什么 | `app/.../ui/RoomsActivity.kt` |
| 聊天页长什么样 / 消息气泡 | `app/.../ui/ChatActivity.kt`、`MessageAdapter.kt` |

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
- `:app-net` 是**唯一能同时碰协程和 Android API** 的联网模块：NSD 发现（resolve 单飞队列用
  `Channel` + 挂起 `for` 循环），以及聊天室这一层（协议 / 房间规则 / 会话）。`ChatRoom` 刻意
  不碰 socket（只收一个 `send` 回调），所以房间规则能在 JVM 上单测；`ChatSession` 才把它们缝起来。
- `:app` **只有 UI**：不解析协议、不判断谁能进房间，`ChatActivity` 只做 `state.collect { render }`。
- 线程：`TcpServer.start` / `Tcp.connect` 是**阻塞**的，别在主线程调（`ChatSession` 已放进协程）；
  `ServiceAdvertiser` / `ServiceDiscovery` 的方法**必须**在主线程调（NsdManager 要 Looper），
  `ChatSession.close()` 也是（它要注销广播）。`Peer` 的回调在那条连接的读线程上按序到达，
  NSD 的回调已经替你抛回主线程，UI 侧收的是 `StateFlow`，所以哪条线程都不用手动切。

## 当前刻意简化的地方

- 界面：无动画、无音效；聊天记录不落盘（退出房间即清空）；最多 8 人。
- 转屏靠 `android:configChanges` 不重开（聊天页尤其重要：重建会 `close()` 掉房间）。
- 联网：只有"行协议 + socket + 房主回环 + NSD 发现 + 聊天室"这几块，
  **断线重连、离线消息、房主权威转移都没写**；房主退出 = 房间解散。
- 进程被杀后再回到聊天页，会按 Intent 重新开一个同名房间（历史消息不保留）。

## 版本管理约定

依赖坐标和版本只写在 `gradle/libs.versions.toml`，模块里不允许出现带版本号的依赖。
插件版本在根 `build.gradle.kts` 用 `apply false` 声明。
