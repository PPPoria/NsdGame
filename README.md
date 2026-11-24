# 茯苓游戏（NsdGames）

局域网多人小游戏集合。现在跑通的是**游戏准备房间**：搜/开房 → 进房间排座、选游戏、设玩法、
准备/开局 → （对局本身还没写，见下文「下一步」）。

设计目标只有一个：**一个人一个周末能看懂、改得动、测得出**。
所以模块就四个、规则就一个文件、界面就两张布局，没有 DI / EventBus / Repository / UseCase。

> 想接着做／想改房间？先读 **`docs/room-reference.md`**：文件地图、快照协议、
> 线程约定、接一个游戏的步骤、真机验证过的范围，都在那一份里。
>
> 想给新页面加一套联网能力（对局页、观战页…）？读 **`docs/handler-channel-guide.md`**
> 《为你的 Activity 设计 Handler / Channel》：协议怎么切、`TcpHandler` 的每个回调、
> payload 怎么收发、图片怎么推拉。

```
core/                     纯 Kotlin 内核：一行一条消息怎么收怎么发。零 Android 依赖，**不含业务**
  src/main/kotlin/com/poria/net/          ★ 联网通信（纯 JVM，本机就能测）
    Wire.kt               行协议：一条消息 = 一行 UTF-8 + '\n'，单条上限 8KB
    Peer.kt               一条连接：收发 + 一条读线程，回调按序
    PeerListener.kt       连接事件 onOpen/onLine/onClosed——房主 UI 和客人 UI 用同一个接口
    TcpServer.kt          房主：监听端口，广播给所有连接
    Tcp.kt                客户端：Tcp.connect(host, port, listener) 拿到一条 Peer
    CloseCause.kt         LOCAL / REMOTE / ERROR，决定要不要重连
  src/main/kotlin/com/poria/core/        时间 / 颜色 / 哈希这类纯工具
  src/test/kotlin/com/poria/             行协议 + 真 socket 测试（JVM 上秒跑）

game-core/                游戏域内核：**有哪些游戏 + 每款游戏的规则**。纯 Kotlin（不碰 Android / 不碰 socket）
  src/main/kotlin/com/poria/game/
    GameCatalog.kt        ★ 游戏目录：能开哪些游戏、几个人玩、有哪些自定义玩法（加游戏只改这里）
    azul/                 （对局规则引擎；状态可以直接 `@Serializable`，编码仍在 `:app-net`）

app-net/                  局域网层：发现（NSD）+ 链路（路由）+ 频道（大厅 / 资料 / 图片 / 会话）
  src/main/java/com/poria/netnsd/
    Nsd.kt                服务类型串 + FoundHost
    ServiceAdvertiser.kt  广播"我开了房间"+ TCP 端口
    ServiceDiscovery.kt   搜"谁开了房间" → FoundHost(ip, port)（resolve 单飞队列）
    link/Frame.kt         ★ 链路帧：FRAME|{v,ns,to,from,p} + `_link` 小协议（hello/welcome/ping/pong/bye）
    link/TcpHandler.kt    ★ 频道契约：PlayerRef / TcpHandler / LinkOut（发件箱）
    link/TcpLink.kt       ★ 链路：socket、peer 身份、按 ns 路由、心跳、中转、关闭（不碰 NSD，可 JVM 测）
    blob/BlobChannel.kt   ★ 图片频道（ns=blob）：谁有谁发、3KB 分块、进度/完成/失败
    blob/BlobCommands.kt  图片的通用用法（`Session.imageFile/requestImage/images/imageProgress`）
    blob/BlobStore.kt     ★ 图片仓库：内容寻址（sha1）、分块拼装、hash 校验、LRU 配额
    blob/BlobFrames.kt    blob 频道的负载（want/chunk/missing/failed）+ 分块读文件
    blob/BlobLimits.kt    chunk 大小 / 单图上限 / 缓存配额（共用一份）
    lobby/Lobby.kt        ★ 服务器侧规则：座位/座次/玩法/准备/踢人/开局（纯状态机，不碰 socket）
    lobby/LobbyProtocol.kt ★ `lobby` 频道负载：命令 / 事件 / 快照编码（超预算时丢最老的聊天）
    lobby/LobbyChannel.kt  ★ `lobby` 频道：服务端半边（改规则推快照）+ 客户端半边（快照→LobbyState）
    lobby/LobbyCommands.kt 大厅动作（`Session.say/setReady/…`）：全是扩展函数，不长胖会话
    lobby/LobbyState.kt   UI 要渲染的东西：本机连接状态 + 服务器快照（`LobbySnapshot`：id + 座位）
    profile/ProfileFrames.kt  `profile` 频道的负载 + `Profile`（昵称 / 签名 / 头像 id）
    profile/ProfileChannel.kt ★ `profile` 频道：资料推（put→广播）+ 拉（want→本人回答），服务器只中转
  Session.kt              ★ 进程级连接持有者：唯一的那条 `TcpLink` + blob/profile/lobby 三个频道 + NSD 广播
  src/test/java/com/poria/netnsd/   帧路由/中转/心跳 + 图片/大厅/资料频道 + 大厅规则（真 socket，JVM 直接跑）
  （详见 app-net/README.md）

base/                     Android 基础设施（可复用的，跟游戏无关）
  BaseActivity/BaseFragment/BaseDialog、dp2px、防抖点击、DSManager、ThreadPool
  res/values/dimens.xml  ★ 圆角/间距/字号的唯一来源（corner_s / corner_m / corner_l）
  res/drawable/bg_corner_*  纯色 + 圆角的通用底：房间页的弹窗、拖拽把手都用它

app/                      唯一的应用模块：UI + MVI 的 ViewModel
  src/main/java/com/ppporia/nsdgames/
    App.kt                       ★ 进程里唯一的 `App.session`（放伴生对象里，调用点不用 cast）
    ui/rooms/RoomsActivity.kt    首页（纯 View 层）：渲染 + 把动作转给 RoomsVM；可见即 `session.leave()`
    ui/rooms/RoomAdapter.kt      房间列表适配器
    ui/room/RoomActivity.kt      游戏准备房间（纯 View 层，房主与客人共用）
    ui/room/SeatAdapter.kt       座位条：座位号 = 出手顺序（有头像就画头像）
    ui/room/MessageAdapter.kt    聊天列表（自己的靠右、别人的靠左带昵称）
    ui/room/RoomSettingsSheet.kt 底部弹出的房间设置（选游戏 / 牌桌人数 / 玩法开关）
    ui/room/SeatSheet.kt         点座位弹出的动作面板（入座 / 换座 / 请人离开）
    ui/Avatars.kt                头像处理：居中裁正方形 + 缩到 ≤512px + WebP
    viewmodel/rooms/RoomsVM.kt   ★ 首页的 MVI ViewModel：建连接、昵称防抖落盘、房间列表、头像编码
    viewmodel/room/RoomVM.kt     ★ 房间的 MVI ViewModel：拼 lobby/profile/link/blob 四条流 → RoomUiState
    profile/ProfileStore.kt      昵称/头像的 DataStore 读写
```

依赖方向：`:app` → `:base` → `:core`，`:app` → `:app-net` → `:core`，
以及 `:app` → `:game-core`、`:app-net` → `:game-core`（规则不依赖任何模块，只被别人依赖）。
反向依赖编译不过。

联网的分层（谁该知道什么）：

```
ServiceDiscovery/ServiceAdvertiser  只知道"服务名 + IP + 端口"（Android NSD）
        ↓ FoundHost(host, port)
TcpServer / Tcp.connect             只知道"连上、发一行、收一行"（纯 JVM socket）
        ↓ Peer（服务器和客户端拿到的是同一个类型）
TcpLink                             只知道"谁是谁、这一条发给谁、按 ns 交给谁"（Frame/心跳/中转）
        ↓ Frame(ns, p) 交给对应频道
Lobby / LobbyProtocol / LobbyChannel 只知道"谁坐哪、设置是什么、该发给谁"（纯 Kotlin，不碰 socket）
ProfileChannel                       只知道"某个 id 的昵称/签名/头像 id 是什么"（显示用，不参与判定）
BlobChannel                          只知道"按内容 id 收发图片"（谁有谁发，服务器只中转）
        ↓ LobbySnapshot（整份权威快照：id + 座位） + Map<id, Profile> + images
Session（App 级）                    持有当前那一条连接：link + blob + profile + lobby + NSD 广播
        ↓ 四条流
RoomVM                              拼装 + 拍平成 RoomUiState（MVI）；**退出页面不收摊**
        ↓
RoomActivity                        只管画和点：collect → render，点击 → vm.xxx()
```

**连接归进程，不归页面**：页面之间不传递链路，谁需要谁从 `App.session.connection` 拿。
所以"房间 → 对局"换页时 socket、身份、图片缓存都还活着；真正收摊只有一处——
回到首页（`RoomsActivity.onStart` → `session.leave()`），也就是"首页可见 = 没有连接"。

## 房主自己也是一个客户端（走回环）

房主手机既是 Host（权威状态 + `TcpServer`），也是玩家。**不要让 UI 直接读 Host 的状态**，
否则会出现"两份真相""本机能跑联机就炸""房主权限散在 UI 里"。正确形状：

```
房主手机：
  TcpServer ──┬── 远端客人（LAN socket）
              └── 房主自己（127.0.0.1 回环，同一个 Tcp.connect）
                    ↓ 都是一条普通 Peer
  TcpLink 上的频道（和客人走完全同一条 命令 → 校验 → 广播 路径）
```

```kotlin
// 房主（Session.host 内部就是这么干的）
val port = link.host(0)        // TcpServer + 回环客户端 + 报到，一条龙
advertise(port)                // 端口交给 NSD 去广播

// 客人
link.join(ip, port)
```

"房主特权"（改设置、踢人、开局）是**服务端侧的策略**：`Room` 记住哪个 playerId 是房主
（链路认出来的那条回环连接），收到命令时校验发送方，而不是让房主 UI 绕过连接去改状态。
房主退出 = 服务端消失（房间解散）；权威转移明确不做。

**房主那条连接为什么不走内存管道**：做过，又撤了。省下的是每方向几 KB 内核缓冲和几个
系统调用，代价是多一条链路（管道阻塞语义、关闭语义、背压上限）要跟 socket 对齐并长期维护。
回合制消息量下这点开销可以忽略，**一条代码路径更值钱**。`TcpTest` 里有一条
`房主用回环连自己的服务端，和远端客人没有区别` 盯着这件事。

## 房间：一切以服务器快照为准

旧版聊天室让客户端拿 `JOINED` / `LEFT` / `LOG` 一串增量事件**自己攒花名册**——
攒错一条就永远两边不一致（排序、重连、丢包都会踩）。现在改成了**快照协议**：

```
客户端 → 服务器（只是"请求"，能不能改由 Lobby 说了算）
  Frame(ns="lobby", p={"type":"join"})
  Frame(ns="lobby", p={"type":"say","text":"开一局？"})
  Frame(ns="lobby", p={"type":"start"})

服务器 → 房间成员
  Frame(ns="lobby", p={"type":"snap","lobby":{…}})                       ← 整份快照
服务器 → 某个人
  Frame(ns="lobby", from="<服务器>", p={"type":"rejected","reason":"…"}) ← 那条命令没生效
```

> 措辞：**服务器**是跑规则、推快照的那个进程；**房主**是"服务器所在机器上的那个玩家"，
> 他自己也走同一条客户端路径（回环）。命令永远发给服务器，事件永远由服务器发出——
> 两者的区别见 `docs/handler-channel-guide.md` 第 0 节。

消息走 `TcpLink` 的帧（`FRAME|{"v":1,"ns":…,"to":…,"from":…,"p":…}`）：
`ns` 决定交给哪个频道（`lobby` / `profile` / `blob` / 以后每个游戏一个），链路不解析 `p`。

三条设计决定，改协议前先想清楚：

1. **客户端没有"我的花名册"**。它只有"最近一次收到的快照"，UI 直接照着画。
   发命令不改本地状态——等服务器把新快照推回来（含发起者自己）才动界面。
2. **`playerId` 由服务器分配**（连上就发 `_link` 的 `hello`，服务器回一条 `welcome` 带着 `p1`/`p2`…）。
   身份只有这一个来源：客户端不自己编，上层（`Lobby`、对局频道）拿到的都是链路认可过的 id。
   `TcpLink.playerId` 一建好链路就有值，各频道通过 `onLinked(localId)` 拿到同一份。
   座位是可变的（服务器排座次），身份不可变。
3. **房主是链路认的**：服务器启动时现场生成一份凭据，只有自己那条 `127.0.0.1` 回环连接把它带回来
   （凭据不上别人的手），那条连接才是房主。`LobbyState.iAmHost` 也只看快照里的 `hostId`——
   UI 灰按钮只是顺手，校验在服务器。

**快照的权威范围尽量小**：房名、房主 id、阶段、游戏 id、牌桌人数、`startHint`（服务器算好的
"现在还开不了局"的原因，空串 = 能开）、玩法开关、按座位排序的**人（只有 id + 座位）**、
准备好了的 id 列表、聊天记录（`seq` + `playerId` + 文本）。
**昵称和头像不在里面**——那是 `profile` 频道的事，它们不影响任何判定，也不该让一份快照卡住。
**`startHint` 由服务器算**，客户端拿它显示在「开始游戏」按钮上，不重复判一遍规则
（所以它写的是"还有 2 人没准备"，不是人名——名字不归这个频道）。

**负载是 JSON**（kotlinx.serialization，`@Serializable` 数据类）：`LobbySnapshot` / `LobbyProtocol.Command`
就是线上那一份，没有手写的字段拼接和转义表，房名/聊天里带 `|`、引号、反斜杠都不怕；
加字段只改数据类（`ignoreUnknownKeys` 保证新旧版本不会互相炸）。快照整行仍有 8KB 上限，
**超预算时从最老的聊天开始丢**——人和设置一条不少（JSON 比手写格式多几十字节/条，聊天能留的条数会少一点）。

上限：`LobbyProtocol` 内容 300、房名 24、错误 80 字符（playerId 由链路分配，`p1`/`p2`…），
快照目标 6KB；`ProfileLimits` 昵称 12、签名 40、头像 id 64。

## 资料（昵称 / 签名 / 头像 id）：`profile` 频道

它和房间**完全分开**，理由是个真 bug：以前昵称和头像 id 塞在房间快照里，
头像还没装好就进房了，别人只能等**下一份快照**（可能要等到有人说句话）才知道他有头像。
拆开之后，资料什么时候就绪什么时候走，**推**和**拉**各管一半：

```
推（我愿意公开）   客户端 → 服务器   Frame(ns="profile", p={"type":"put","profile":{…}})
                 服务器 → 所有人   Frame(ns="profile", p={"type":"info","id":"p2","profile":{…}})

拉（我想看看）     客户端 →（中转）→ 本人  Frame(ns="profile", to="p2", p={"type":"want","playerId":"p2"})
                 本人 →（中转）→ 问的人  Frame(ns="profile", from="p2", p={"type":"info","id":"p2","profile":{…}})
```

- **推**用在"我的资料变了"：改了头像再 `put` 一次，所有人当场更新，不用等谁的快照；
- **拉**用在"我刚进来，名册上这些人一个都不认识"：按 id 去问。
  **对方可以选择不答**（不想公开就不回，问的人一直显示占位）——这就是匿名模式，
  不需要额外的消息类型；问的人会记住"已经问过"，名册怎么变都不会变成请求风暴；
- 两条路的回答都是 `info`，接收侧只有一条路径；
- **权威归本人**：`put` / `want` 里都没有"我是谁"，服务器用连接上认到的 id 覆盖落款，
  所以谁也改不了别人的资料；服务器**不存资料**，只做"把 `put` 变广播、把 `want` 转给本人"；
- 头像只有 id，图片本体走下面的 `blob` 频道；谁拥有这张图就是资料表的 key（本人）。

## 图片（头像 / 自定义砖块纹理）

图片走**常驻频道 `blob`**：谁有谁发，服务器只做中转（链路 `TcpLink` 的 `sendTo`）。

```
缺图的一方   out.sendTo(ownerId, {"type":"want","id":"<sha1>"})
拥有者       out.sendTo(请求者, {"type":"chunk","id":…,"index":0,"total":14,"size":41000,"data":"<base64 3KB>"})
收齐         BlobStore 验 sha1 → progress 里那条消失 → ready 里出现 id
要不到       {"type":"missing"} / {"type":"failed"} → failed 里出现原因
```

- **谁有谁发**：头像 id 从 `profile` 频道来，本机没有就去向**本人**要（内容寻址，谁发都行）；
- **快照里只有 id**（`sha1(图片)` 的十六进制，或 `builtin:` 开头的打包素材），没有字节：
  50 张图不会乘上房间人数；`builtin:` 的图根本不过网，UI 直接从 APK 资源加载。
- **一块 3KB**（base64 后 4KB、一行约 4.2KB）：单次阻塞写只有几毫秒；发送在频道自己的 IO 协程里，
  同一 `(收件人, id)` 只允许一条流（交错会打乱块序）。
- **id 就是内容**：接收方拼完自己验 sha1，所以**谁发都行、服务器不可信也传不坏**；服务器不用替别人存图。
- **能看进度**：`BlobChannel.progress` 是 `StateFlow<Map<String, BlobProgress>>`，
  里面有 `received / total` 字节数和 `percent`；收齐进 `ready`、要不到进 `failed`。
- 客户端发图前请先缩到 **≤512px** 再编码（512px WebP/JPEG 一般 20–60KB ≈ 10–20 块）。
- 用法只有一套：`Session.imageFile / requestImage / images / imageProgress`（`blob/BlobCommands.kt`）。

## 座位 / 玩法 / 游戏目录

- **座位号就是出手顺序**。牌桌人数由游戏决定（花砖物语 2–4 人，默认 4 个座位），
  空座位也画出来；房主把两个人一换就是排座次（`swap`）。
- **准备 / 开局**：客人点「我准备好了」，服务器算出 `startHint`（人不够 → "还差 N 人"；有人没准备 →
  "还有 N 人没准备"；都齐了 → 空串），房主按钮上直接显示这个原因；按「开始游戏」时服务器再校验一遍。
  改人数、换游戏、开关玩法会清空所有人的准备状态（同一条规则）。
- **游戏目录在 `:game-core` 的 `GameCatalog`**：加一款游戏 = 加一条 `GameInfo`（人数范围 + 玩法开关
  的默认值）。首页的"今天能开"、设置面板的"选游戏 / 选人数 / 玩法开关"都照着它渲染，不用改 UI 代码。

## 下一步：把对局接进来

`START` 现在会过完"人数够、都准备好了"的校验，然后返回一句
「花砖物语的对局还在施工」。接游戏的步骤在 `docs/room-reference.md` 第 7 节，一句话版：

1. `Lobby.start()` 把 `return` 换成 `phase = LobbyPhase.Playing` + `bumpLocked()`；
2. 新加一个频道（`AzulChannel`，`ns = "azul"` + `AzulProtocol` 的负载），对局页的 ViewModel
   `link.register(...)` / `onCleared` 时 `unregister("azul")`；
3. 对局页从 `App.session.connection` 拿链路（**不是**从房间页传过去），所以换页时
   socket、身份、图片、资料都还活着，回大厅也就退了对局页而已。

链路、大厅、资料、图片都不用动，发命令和推快照还是"命令 → 规则 → 快照"那一套。

## 跑起来

```bash
./gradlew :core:test          # 行协议 + 真 socket 测试，几秒跑完，不需要模拟器
./gradlew :game-core:test     # 游戏目录/规则（同样是纯 JVM）
./gradlew :app-net:test       # 各频道协议 + 快照裁剪 + 大厅规则 + 真 socket 收发（JVM 单测）
./gradlew :app:assembleDebug  # 打包 APK
```

## 想改东西时改哪个文件

| 想改什么 | 改这里 |
|---|---|
| 消息怎么切（分隔符、长度上限） | `core/.../net/Wire.kt` |
| 谁连谁、连接/广播/断开 | `core/.../net/TcpServer.kt`、`Tcp.kt`、`Peer.kt` |
| 房主自己怎么连自己 | `TcpLink.host(0)` 里 `Tcp.connect("127.0.0.1", port)`，没有特殊代码 |
| 加游戏 / 改人数范围 / 加玩法开关 | `game-core/.../game/GameCatalog.kt` |
| 链路（连接、心跳、谁发给谁、中转） | `app-net/.../link/TcpLink.kt`、`Frame.kt`、`TcpHandler.kt` |
| 连接什么时候建、什么时候断 | `app-net/.../Session.kt`（`App.session`）+ `RoomsActivity.onStart` 的重置 |
| 房间协议（加一条命令 / 快照加字段） | `app-net/.../lobby/LobbyProtocol.kt` |
| 房间规则（谁能改什么） | `app-net/.../lobby/Lobby.kt`（纯状态机，不碰网络） |
| 房间广播 / 踢人 / 拒收 | `app-net/.../lobby/LobbyChannel.kt` |
| 房间页的动作（发命令） | `app-net/.../lobby/LobbyCommands.kt` |
| 昵称 / 签名 / 头像 id（资料协议） | `app-net/.../profile/ProfileFrames.kt`、`ProfileChannel.kt` |
| 图片分块传输 | `app-net/.../blob/BlobChannel.kt`、`BlobStore.kt`、`BlobFrames.kt` |
| 页面要用图片（取文件 / 要图 / 进度） | `app-net/.../blob/BlobCommands.kt`（订阅 `App.session` 上的那一个频道，**别新建**） |
| 房间怎么被局域网发现 | `app-net/.../ServiceAdvertiser.kt`、`ServiceDiscovery.kt` |
| 首页长什么样 / 列表点了之后干什么 | `app/.../ui/rooms/RoomsActivity.kt`、`viewmodel/rooms/RoomsVM.kt` |
| 房间页长什么样 / 座位条 / 设置面板 | `app/.../ui/room/RoomActivity.kt`、`SeatAdapter.kt`、`RoomSettingsSheet.kt`、`SeatSheet.kt` |
| 房间状态怎么拼装、拍平成 UI 状态 | `app/.../viewmodel/room/RoomVM.kt`（`RoomUiState`） |
| 新页面要加一套协议 | 照 `docs/handler-channel-guide.md` 抄 |
| 圆角 / 间距 / 字号 | `base/res/values/dimens.xml`（**别在 app 里写魔法值**） |

## 架构硬约束（以后让 AI 写代码时把这段贴给它）

单人项目，边界由人定，AI 只填实现。**以下每一条都是"不许"**：

1. 只有 `:core` / `:game-core` / `:base` / `:app-net` / `:app` 五个模块，**禁止再增**。
   分模块的理由只有两条：
   - **依赖不同**：`:core` 不碰 Android（所以 `:core:test` 秒跑），`:app-net` 必须碰 Android（`NsdManager`），
     `:base` 是界面基建；
   - **变化速率不同**：`:core`（通信）已经稳定、几乎不改；`:game-core`（有哪些游戏 + 规则）随每加一款
     游戏长大。它俩都不碰 Android，靠变化速率分而不是靠依赖分。`:game-core` 只允许依赖
     `kotlinx-coroutines-core` + `kotlinx-serialization-core` 两个**纯 Kotlin 库**，
     **不许依赖任何其它模块**（这样拆出来零成本、也不会造成循环）。
    说不清属于哪一条就别拆。
2. **依赖只许这几样**：AndroidX 基础库、Material、kotlinx-coroutines，外加**唯一一个例外**
   `kotlinx-serialization`（`@Serializable` 数据类直接上线，不手写拼装与转义）。
   socket 用 JDK 自带的 `java.net`，网络发现用系统的 `android.net.nsd`，除此之外不引库。
   按模块落地：
   - `:core`：零依赖（连协程都没有，见下面"联网分三层"）；
   - `:base`：AndroidX + `kotlinx-coroutines-android`；
   - `:game-core`：`kotlinx-coroutines-core`（**纯 Kotlin，不是 Android 那个 artifact**）+
     `kotlinx-serialization-core`（只打注解，**不引 json**，也不能用 `Dispatchers.Main`）；
   - `:app-net`：`kotlinx-coroutines-android` + `kotlinx-serialization-json`（全局唯一的 Json 实例在这）；
   - `:app`：AndroidX + Material，**自己不引协程/序列化**（都从下面带上来）。
3. 禁止 Hilt / Koin / Dagger、RxJava、EventBus、Room、Navigation。
4. 禁止"只有一个实现的接口"、禁止 UseCase / Repository / Mapper 分层、禁止自己写 DI 容器。
   （`TcpServer.Listener` / `ServiceDiscovery.Listener` 这类回调接口不算：它们是给调用方覆盖的，不是拿来注入的。）
5. 规则判定只能写在 `:game-core`。UI 或 session 里出现"这张牌能不能出""现在能不能开局"的判断就是 bug——
   应该问 `GameCatalog` / 让服务器的 `Lobby` 给出 `startHint` 或拒绝原因。
   `:core` 里**一行游戏规则都不许有**（它只管"一行一条消息怎么收发"）。
6. **客户端不许有自己的房间状态**。名册、座位、设置、聊天记录都只能来自服务器推来的快照；
   客户端"先改本地再等服务器确认"是 bug（两份真相）。页面（Activity）连本地数据都不许攒：
   状态在 ViewModel 里、ViewModel 的状态来自快照，Activity 只渲染，最多留一个"最近一次状态"
   给弹面板时读一眼。
7. 新文件先按 200 行估一遍，超了就想想是不是在造框架。
8. 每个功能一次做完再做下一个，不要预留"以后可能要"的扩展点。
9. **图片不进快照**：快照里只放 id，字节走 `blob` 分块；一块 ≤3KB（base64 后 ~4.2KB），
   别把 8KB 行上限当"传输容量"用——分块才是不堵别人的关键。
10. **连接归进程，不归页面**：`TcpLink` 只由 `Session`（`App.session`，进程单例）创建和关闭，
    页面/VM 一律"用而不持有"；页面自己的频道（比如将来的对局频道）由那个 VM
    `register` / `unregister`，但**不许关链路**。收摊只有一个入口：回到首页。
    这一条**不靠自觉**：`Session` 的构造器是 `private`、`TcpLink` 的构造器和
    `host/join/close` 都是 `internal`，`:app` 里写 `TcpLink()` 或 `link.close()` 直接编译不过。
11. **房间的权威范围要小**：`lobby` 只认"身份 / 座位 / 聊天 / 房间设置"。
    昵称、头像、签名这类**不影响判定**的东西一律另开频道（现在就是 `profile`），
    别塞进快照——塞进去的话"资料晚到"就会变成"房间状态卡住"。判据很简单：
    **这条数据会不会改变"谁能做什么"？** 不会，就别进快照。

审查时只问五句话：这个抽象有第二个实现吗？这个类能一句话说清职责吗？新增依赖了吗？
规则判定有没有散到 UI / 网络里？客户端是不是又在偷偷存状态？

## 联网分三层，别越界

- `:core` 的 `com.poria.net` **只搬字符串**：不认识游戏，不认识 Android，**也不引协程**
  （阻塞写 = 天然背压；换 channel 就得回答"队列满了丢哪条消息"）。消息体是什么格式
  由上层决定（大厅这边是 JSON），这一层只管"一行一条、收发、断开"。
- `:game-core` **只有数据和规则**：不认识 socket、不认识 Android、不认识 JSON。
  它答的是"这个游戏几个人玩、这步合不合法"，谁想发给谁完全是别人的事。
- **一条链路，一个类型**：远端客人、服务器自己（`127.0.0.1` 回环）都是 `Tcp.connect` 拿到的
  `Peer`，`PeerListener` 也是同一个接口——**会话代码里不允许出现"这是房主所以走另一条路"的分支**。
- `:app-net` 是**唯一能同时碰协程和 Android API** 的联网模块：NSD 发现（resolve 单飞队列用
  `Channel` + 挂起 `for` 循环），以及各频道这一层（协议 / 频道）。`Lobby` 刻意
  不碰 socket（只改自己的状态，规则来自 `:game-core`），所以大厅规则能在 JVM 上单测；
  `LobbyChannel` 才把"谁该收到"接上，`Session` 只负责建/关和持有。
- `:app` **只有 UI + MVI**：`RoomVM` / `RoomsVM` 从 `App.session` 拿连接、拼状态、转发动作，
  Activity 只做 `vm.state.collect { render }` + 把点击转给 VM；不解析协议、不判断谁能改设置
  （按钮灰不灰跟着快照走）、不持有链路。
- 线程：`TcpLink.host` / `TcpLink.join` 是**阻塞**的，别在主线程调（`Session` 已经放进协程）；
  `ServiceAdvertiser` / `ServiceDiscovery` 的方法**必须**在主线程调（NsdManager 要 Looper），
  所以 `Session.leave()` 自己切主线程。`Peer` 的回调在那条连接的读线程上按序到达，
  NSD 的回调已经替你抛回主线程，UI 侧收的是 `StateFlow`，所以哪条线程都不用手动切。

## 当前刻意简化的地方

- 界面：无动画、无音效；聊天记录不落盘（退出房间即清空）；牌桌最多 6 座，花砖物语 2–4 人。
- 本机资料（昵称、头像）落在 DataStore（`DSManager`）：头像转 base64 存成字符串，昵称输入防抖 500ms 写一次；
  换设备/重装就没了（没有账号体系）。
- 转屏靠 `android:configChanges` 不重开（订阅不重来；连接在 `App.session` 上，本来也不跟页面走）。
- 联网：只有"行协议 + socket + 房主回环 + NSD 发现 + 房间快照"这几块，
  **断线重连、离线消息、房主权威转移都没写**；房主退出 = 房间解散。
- 对局规则还没写：`START` 只做校验然后给一句提示（`GameInfo.available = false`）。
- 进程被杀后再回到房间页：`App.session` 里没有连接了，页面提示一句就退出（不自动重连）。

## 版本管理约定

依赖坐标和版本只写在 `gradle/libs.versions.toml`，模块里不允许出现带版本号的依赖。
插件版本在根 `build.gradle.kts` 用 `apply false` 声明。
