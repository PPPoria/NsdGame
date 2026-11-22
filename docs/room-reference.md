# 游戏准备房间：完整参考样例

> 这份文档是给**下一个接手的人或 AI** 看的。代码本身是"地基 + 一个能跑通的样例"，
> 不是要扩张的框架。看完再动手；改之前先读根 `README.md` 的「架构硬约束」。
>
> 这一版把旧的"聊天室"改成了"游戏准备房间"，最关键的改动是协议：**客户端只渲染服务端快照**，
> 不再自己维护花名册。第 3 节是整个设计的心脏。
>
> 传输层也换了一代：所有东西都跑在一条**链路**（`TcpLink`，按 `ns` 路由的 `Frame`）上，
> 房间只是其中一个频道（`lobby`），图片是另一个（`blob`）。第 3 节从帧讲起。
>
> 连接归进程（`App.session`），不归页面：换页不传 socket，回首页才收摊（见第 4.3 节）。

## 1. 它是什么

两个页面 + 一套房间协议，跑在局域网里：

```
手机 A（房主）                                手机 B（客人）
┌──────────────────────────────┐             ServiceDiscovery
│ TcpLink.host(0)  监听端口      │             ── 看到 FoundHost(ip, port)
│   ├─ 远端客人 ────────────────┼─── LAN ──── 然后 TcpLink.join(ip, port)
│   └─ 房主自己走回环            │
│        ("127.0.0.1", port)    │
│ 频道：lobby（房间）+ blob（图） │
│ Room   座位/玩法/准备/踢人     │
│ ServiceAdvertiser 广播端口     │
│ App.session → RoomVM → UI     │             App.session → RoomVM → UI
└──────────────────────────────┘
```

**房主自己也是一条普通客户端连接**（走 `127.0.0.1` 回环），所以：
房主 UI 和客人 UI 拿到的是同一个 `RoomState`、同一个页面代码，
会话里不存在 `if (isHost)` 的分支（唯一的区别是房主那条链路还带着 `Room` 这个权威）。

房间能做的事：聊天、排座次（座位号就是出手顺序）、选游戏、改牌桌人数、开关自定义玩法、
准备、踢人、开局（开局目前只做校验，规则还没写）。

## 2. 文件地图与依赖方向

```
:app     ──→ :base   ──→ :core          反向依赖编译不过
         └─→ :app-net ──→ :core

core/.../net/            纯 JVM，零依赖（不引协程）
  Wire.kt                行协议：一行 UTF-8 + '\n'，单条上限 8KB
  Peer.kt                一条连接：send/isOpen/close + 一条读线程，回调按序
  PeerListener.kt        onOpen / onLine / onClosed / onServerDown
  TcpServer.kt           监听、accept、broadcast、连接表
  Tcp.kt                 Tcp.connect(host, port, listener): Peer（阻塞，别在主线程）
  CloseCause.kt          LOCAL / REMOTE / ERROR

core/.../game/
  GameCatalog.kt         游戏目录：GameInfo（人数范围/玩法开关）、GameOption、默认值

app-net/.../            可用协程 + Android API
  Nsd.kt                 服务类型串 + FoundHost(serviceName, host, port)
  ServiceAdvertiser.kt   注册 NSD 服务（带端口），注销
  ServiceDiscovery.kt    搜服务 → FoundHost；resolve 单飞队列（Channel + 挂起 for）
  room/LobbyProtocol.kt  `lobby` 频道的负载：命令 / 事件 / 快照编码 + 超预算裁剪（纯逻辑，可测）
  room/Room.kt           房主侧规则：座位、座次、玩法、准备、踢人、开局（纯状态机，不碰 socket）
  room/LobbyChannel.kt   `lobby` 频道：服务端半边（改规则 → 推快照）+ 客户端半边（快照 → RoomState）
  room/RoomState.kt      RoomSnapshot / RoomPlayer / RoomMessage + UI 侧 RoomState（`mergedWith` 叠链路状态）
  room/RoomCommands.kt   房间动作的扩展函数：`Session.say/setReady/…` + 头像请求
  Session.kt             ★ 进程级连接持有者：唯一那条 TcpLink + BlobChannel + LobbyChannel + NSD 广播

app-net/.../link/        链路层（与房间无关，可独立测试）
  link/Frame.kt          FRAME|{v,ns,to,from,p} + `_link` 小协议（hello/welcome/ping/pong/bye）
  link/TcpHandler.kt     PlayerRef / TcpHandler / LinkOut（频道契约）
  link/TcpLink.kt        socket、peer 身份、按 ns 路由、心跳、中转、关闭（不碰 NSD）

app-net/.../blob/        图片频道
  blob/BlobChannel.kt    ns="blob"：谁有谁发、3KB 分块、进度/ready/failed
  blob/BlobStore.kt      内容寻址仓库（sha1、分块拼装、hash 校验、LRU）
  blob/BlobFrames.kt     want/chunk/missing/failed 的负载 + 分块读文件
  blob/BlobLimits.kt     chunk 大小 / 单图上限 / 缓存配额

app/.../ui/rooms/        首页（纯 View 层）
  RoomsActivity.kt       头像 + 昵称 / 创建房间 / 搜到的房间列表
  RoomAdapter.kt         房间列表（ListAdapter）

app/.../ui/room/         房间页（纯 View 层，只渲染 RoomUiState）
  RoomActivity.kt        游戏准备房间（房主与客人共用）
  SeatAdapter.kt         座位条（座位号 = 出手顺序，有头像就画头像）
  MessageAdapter.kt      聊天列表（自己的靠右、别人的靠左带昵称）
  RoomSettingsSheet.kt   底部设置面板：选游戏 / 牌桌人数 / 玩法开关
  SeatSheet.kt           点座位弹出的动作面板：入座 / 换座 / 请人离开

app/.../viewmodel/       MVI 的 ViewModel（状态和订阅都在这里）
  rooms/RoomsVM.kt       首页：建连接（host/join）、昵称防抖落盘、头像编码、房间列表、NSD 错误提示
  room/RoomVM.kt         房间：订阅 lobby/link/blob 三条流，拍平成 RoomUiState + 转发动作（不收摊）

app/.../App.kt           进程里唯一的 `session`（连接放这儿，不跟页面走）
app/.../ui/Avatars.kt    头像：选中图后居中裁正方形 + 缩到 ≤512px + WebP（ImageDecoder 自动转正）
app/.../profile/ProfileStore.kt  昵称/头像的 DataStore 读写
```

## 3. 协议：链路帧 + 频道 + 服务端权威快照

### 3.1 三层：一行 → 帧 → 频道负载

一条消息是一行 UTF-8（`:core` 的 `Wire` 保证 8KB 上限、不会出现换行）。这一行是**帧**：

```
FRAME|{"v":1,"ns":"lobby","p":{"type":"say","text":"开一局？"}}
       │        │          └ 该频道的负载（链路不看，只转交）
       │        └ 频道名：链路按它找 Handler
       └ 帧协议版本
```

| 字段 | 谁填 | 干什么 |
|---|---|---|
| `v` | 链路 | 帧协议版本（`LINK_VERSION`）；对不上就请对方出去 |
| `ns` | 频道 | 频道名，链路**只认它**：按它找到注册的 Handler，把 `p` 原样丢进去 |
| `to` | 发送方 | 收件人（中转用）；空 = 发给房主 / 服务端本身 |
| `from` | 房主 | 谁发的；**客户端填的会被丢掉**，转发时由房主盖章 |
| `p` | 频道 | 负载（JSON）；心跳、清空这类帧没有 |

- **帧里没有"命令还是推送"字段**：方向由"从哪条连接收到"决定——
  服务端那条连接收到 → `onCommand(player, p)`；客户端那条连接收到 → `onEvent(p, from)`。
- **`_link` 是链路保留频道**：`hello`（报到，**不带身份**）/ `welcome`（房主分身份）/ `ping` / `pong` / `bye`（带原因）。
- 现有频道两个：`lobby`（房间，`LobbyChannel`）、`blob`（图片，`BlobChannel`）。
  将来的 `azul` 就是一个新频道：一个类 + `TcpLink.register()`，大厅那套一行都不用改。

### 3.2 `lobby` 频道：命令与事件

> 术语：**服务器** = 跑规则、推快照的那个进程（`link.host()` 起的那个 `TcpLink`）；
> **房主** = 一个玩家，今天他手机上同时跑着服务器，所以他是"服务器的第一个客户端"；
> **客户端** = 其他玩家。命令永远发给服务器，事件永远由服务器发出。（详见
> `docs/handler-channel-guide.md` 第 0 节。）

```
客户端 → 服务器   LobbyProtocol.Command
  join / say / ready / seat / swap / size / game / option / kick / avatar / start

服务器 → 客户端   LobbyProtocol.Event
  {"type":"snap","room":{…}}        唯一的数据通道：每次变化整份推给**房间成员**（含发起者）
  {"type":"rejected","reason":"…"}  那条命令没生效（状态没动），给人看的原因
```

命令的负载长这样（`type` 由 `@SerialName` 生成）：

```
{"type":"join","name":"小明"}
{"type":"say","text":"开一局？"}
{"type":"swap","a":0,"b":3}
{"type":"start"}
```

**只有房间成员收得到快照**：`LobbyChannel.publish()` 用 `broadcastEach`，
链路里报到过但还没进房、刚被拒、刚被踢的人拿不到房间状态。

### 3.3 快照的格式

就是 `RoomSnapshot` 这个 `@Serializable` 数据类的 JSON（放在 `snap` 事件的 `room` 里）：

```
{"version":1,"roomName":"小明的房间","hostId":"p1","phase":"Lobby","gameId":"azul",
 "tableSize":4,"startHint":"还差 1 人","options":{"grey":false,"quick":false,"harsh":false},
 "players":[{"id":"p1","seat":0,"name":"小明","host":true,"ready":false,"avatar":"<sha1>"}],
 "messages":[{"seq":1,"playerId":"p1","text":"开一局？"}]}
```

- `version`：`LobbyProtocol.VERSION`（现在 `1`）；**认不出就整份丢掉**，宁可不动也不渲染半份状态；
- `phase`：`RoomPhase` 的**枚举名**（现在只会是 `Lobby`；对局阶段等规则写好再用）；
- `startHint`：服务端算好的"现在还开不了局"的原因——"还差 N 人" / "等 X、Y 准备" / 空串（能开）。
  客户端**只负责显示**（房主按钮上就写它），不自己再判一遍；
- `players` 按座位排序；`messages.seq` 由服务端自增（UI 靠它做列表 diff）。

**为什么不用手写拼装**：旧方案要自己定分隔符、自己写转义表（`% | ; , =` + 换行），
字段一多就是"两处（编码/解码）要手工对齐"，漏一个就是 `decode → null`、客户端卡在旧快照上。
换成 `@Serializable` 之后：加字段只改数据类（给个默认值就行），
`Json { ignoreUnknownKeys = true; encodeDefaults = true }` 让新旧版本不会互相炸，
昵称/房名/聊天内容里带什么字符都交给 JSON。

**长度与裁剪**：单行有 8KB 上限（`Wire.MAX_LINE_BYTES`），快照目标是 6KB
（`LobbyProtocol.MAX_SNAPSHOT_BYTES`，按**整帧**字节数算）。超了就从**最老的聊天**开始丢，
人和设置一条不少（`LobbyProtocol.encodeSnapshot` 里那个 while 循环）。
`Room.MAX_HISTORY = 40` 是内存里留的上限，实际通常是快照预算先到。

### 3.4 三条设计决定

1. **客户端没有自己的状态**。它只有一个"最近收到的快照"（`RoomState.snapshot`）。
   发命令不改本地——`snap` 会推给所有人（含发起者），等它回来界面才动。
   这样"花名册/座位/设置"永远只有服务端这一份真相，不会有"本机看起来对、对面看起来错"。
2. **`playerId` 由房主分配**：连上就发 `_link` 的 `hello`（不带身份），房主排个号
   （`p1` / `p2` / …）回 `welcome`。身份只有这一个来源——客户端不自己编，
   `TcpLink.playerId` 和 `LobbyChannel.onLinked` 拿到的都是房主认可过的 id。
   座位是可变的（房主排座次），身份不变。
3. **房主由链路认，不由客户端自称**：`TcpLink.host()` 现场生成一份随机凭据塞进 `hello`，
   服务端只有对"回环连上来 + 凭据对得上"的那条连接才打 `isHost`（凭据不上别人的手）。
   `Room.join(host = …)` 的 `host` 就是它。快照里的 `hostId` 只让 UI
   知道该不该显示房主功能；真正的校验在 `Room.hostOnly()` 里。

### 3.5 图片（头像 / 自定义纹理）的通道

**为什么不在快照里塞图片**：快照每次变化都重发（有人说话、换座位、准备……），
图片进快照就是把 50 张图 × 房间人数 的流量摊到每一次改动上，而且立刻顶爆 8KB 行上限。

所以图片走**同一条链路上的 `blob` 频道**，快照里只留 id（`players[].avatar`），
而且**谁有谁发、房主只中转**：

```
A 缺图：   Frame(ns="blob", to=拥有者, p={"type":"want","id":"<sha1>"})
拥有者：   Frame(ns="blob", to=A,      p={"type":"chunk","id":…,"index":0,"total":14,"size":41000,"data":"<base64>"})
要不到：   {"type":"missing","id":…} / {"type":"failed","id":…,"reason":"…"}
```

**一次"设置头像"的完整流程**：

1. 客户端把图缩到 ≤512px、压成 WebP/JPEG（一般 20–60KB），
   `BlobChannel.install(bytes)` 算 `id = sha1(bytes)` 装进本机仓库（自己立刻能画）；
2. `send(SetAvatar(id))` → 房主 `Room.setAvatar` → 广播快照（`players[].avatar = id`）；
3. 别人看到这个 id：`images`（本机能画的）里有就画，没有就自己从快照里找拥有者
   （`players[].avatar == id`），再 `requestImage(id, owner)`；
   → `BlobChannel.request(id, 拥有者)`；
4. 拥有者把文件按 3KB 一块流回来；每收一块更新一次 `progress`，收齐后 `BlobStore` 验 sha1
   → 进 `ready` → `RoomState.availableBlobs` 里就有它了。

几条硬约束（都在代码里，别绕过）：

- **一块 ≤ `BlobLimits.CHUNK_BYTES`（3072）**：base64 后 4KB、一行 ~4.2KB。
  图片大小只决定"多少块"，不决定"单次阻塞多久"。
- **发送方不可信也无所谓**：id 就是内容的 sha1，接收方拼完自己验一遍。
  于是"谁有谁发"才成立，房主不用替所有人存图。
- **同一 `(收件人, id)` 只有一条流**：两条流交替会把块序打乱，`BlobStore` 会整份放弃
  （`BlobStoreTest` 有这条用例）；`BlobChannel.serving` 用 `putIfAbsent` 保证。
- **不走房间锁、不广播**：图片谁也不推给所有人，只推给要图的那一个（`sendTo`）。
- **上限**：单图 4MB、本机 64MB LRU。同一张图（同一个 hash）重复上传直接当完成。
- **`builtin:` 前缀**：打包在 APK 里的素材（花砖的默认纹理）用 `builtin:azul.grey` 这种 id，
  快照里照样只放 id，但**永远不过网**——UI 从资源里加载即可。

**两张小表**（都在 `BlobChannel` 里，没有"JobId"这种东西：协议里真正的身份就是 blob id）：

| 表 | 键 → 值 | 干什么 |
|---|---|---|
| `serving`（拥有者侧） | `"<收件人>\|<id>"` → `Job` | 同一张图对同一个人只发一条流（块序不能交错） |
| `progress`（要图侧） | `id` → `BlobProgress(received, total)` | 下载进度：`fraction` / `percent` / `done` 直接给 UI；30 秒没等到就撤掉占位并进 `failed`，下次重画再要一次 |

`Job` 在这里的价值只有两个：**能取消**（链路关了、超时）和**能判断在跑**。
如果只是"发完就忘"，用 `HashSet<String>` 就够；加上超时/取消之后，`Job` 比手写时间戳开关少一堆状态。

## 4. 状态机（Room → LobbyChannel → Session → VM）

职责四层，从上往下谁都不越界：

```
Room          规则（谁能改什么、结果是什么）        纯 Kotlin，一把锁 + version，不碰 socket
   ↓ snapshot()
LobbyChannel  频道（收命令 → 改 Room → 推快照；收快照 → RoomState）   拿着 LinkOut
   ↓ state: StateFlow<RoomState>（只有房间那一份真相）
Session       （进程级）持有当前连接：link + blob + lobby + NSD 广播    只管建/关，不发命令不拼状态
   ↓ 三条流
RoomVM        拼装（房间 + 链路 + 可画的图）+ 拍平成 RoomUiState       页面退出不收摊
```

### 4.1 Room：什么都不说的权威

```kotlin
Room.join(playerId, name, host): JoinResult   // Joined / Full / DuplicateName / AlreadyJoined / InvalidName / InvalidId
Room.leave(playerId): Boolean
Room.say/setReady/takeSeat/swapSeats/setTableSize/selectGame/setOption/setAvatar/kick/start: String?
Room.version: Int                             // 真的改了东西才 +1
Room.snapshot(): RoomSnapshot
```

- **每个方法返回"没生效的原因"**（`null` = 生效），不返回状态，也不广播；
  空消息、同一个座位这种"成功但没变化"也返回 `null`，只是 `version` 不动。
  所以调用方只要比一下"进锁前的 `version`"就知道该不该推快照。
- `RoomTest` 只对着 `snapshot()` 和返回值断言：不需要 socket，也不需要协程。

### 4.2 LobbyChannel：一个频道一个类，两半都在里面

- **服务端半边**（只有房主有 `Room`）：`onCommand` 解码 → 调 `Room` → 和进锁前的 `version` 比一下，
  变了就 `publish()`；被拒就 `sendTo(playerId, rejected(reason))`。
  `onPeerLeft` → `Room.leave()` → `publish()`。
- **客户端半边**（两边都有）：`onEvent` 收 `snap` → `_state = RoomState(ONLINE, snapshot)`；
  收 `rejected` → 进 `note`；`onLinkClosed` → `CLOSED` + 原因。
- **为什么合成一个类**：房主那条链路上既会收到客人发来的命令（服务端半边），也会收到自己广播出去的
  快照（客户端半边），而 `TcpLink` 的注册表一个 `ns` 只能放一个 Handler。
- **踢人是"先广播、后断开"**：`kick` 让 `Room` 少一个人 → `publish()`（剩下的人看到座位空了）
  → `out.disconnect(target, "房主把你请出了房间")` → 对方链路收到 `_link` 的 `bye`
  → `onLinkClosed("房主把你请出了房间")` → `RoomState.note` 就是这句话。

### 4.3 Session：进程级，只持有不转发

```
Connection { link: TcpLink, blob: BlobChannel, lobby: LobbyChannel }   ← 一条连接的三个东西，同生共死
Session    { connection: Connection?, advertiser: ServiceAdvertiser? } ← App 里唯一的那个
```

- `Session.host(roomName, playerName, avatar)` / `Session.join(target, playerName, avatar)`：
  **挂起**函数，内部 IO 线程建 socket、Main 线程注册 NSD，成功返回 `null`，失败返回一句原因
  （顺手 `leave()` 清干净）。每次都是全新的一套 `TcpLink` + `BlobChannel` + `LobbyChannel`
  ——链路关掉就不能再用，这个坑由这里兜住。
- `Session.leave()`：**唯一收摊入口**，自己切主线程注销 NSD 再关链路；重复调用安全。
- **它不知道房间协议**：`say/setReady/…` 是 `room/RoomCommands.kt` 里的扩展函数，
  状态拼装在 VM，图片进度在 `BlobChannel` 自己身上。以后加对局，`Session` 一行都不用改。
- **这些边界是编译器管的**：`Session` 构造器 `private`（只能 `getInstance(context)`），
  `TcpLink` 构造器和 `host/join/close` 是 `internal`，`Connection` / `BlobChannel` /
  `LobbyChannel` / `BlobStore` / `Room` 的构造器也是 `internal`。`:app` 里写 `TcpLink()`、
  `link.close()`、`BlobChannel(...)` 都编译不过——README 里那条"别新建"因此只是解释，不是防线。
- 本机 id：`link.playerId` 是房主分的，`LobbyChannel` 从 `onLinked` 拿到同一份，
  所以 `RoomState.myId` 在进房之前就有值。
- **为什么它必须比页面活得久**：房主的 `Room`（座位/座次/昵称权威）就活在 `LobbyChannel` 里。
  如果它跟着房间页的 VM 走，一进对局页权威就没了——所以"房间"是会话级的，"页面"只是订阅者。

### 4.4 RoomVM：拼装 + 拍平（MVI）

```
lobby.state: StateFlow<RoomState>  ┐
link.state:  StateFlow<LinkState>  ├─ combine → mergedWith → RoomState → RoomUiState
blob.available: StateFlow<Set<Id>> ┘             （stateIn，WhileSubscribed）
        ↓  collect { render(it) }
RoomActivity                                     （只拼字符串、解 Bitmap、弹面板）
```

- VM 的输入是三条流；**没有一个字段是客户端算出来的房间数据**（`startHint` 也是服务端给的）。
- VM 的副作用只有一件：给缺图的人发 `requestImage`（`BlobChannel.request` 自带去重）。
- **`onCleared` 里什么都不做**：连接属于进程。转屏、退到对局页都不该断别人；
  真正收摊是回到首页（`RoomsActivity.onStart` → `session.leave()`）。
- Activity 里唯一的"状态"是 `private var uiState`（最近一次渲染结果），只用于弹面板前读快照；
  业务数据一律从 VM 来。
- `RoomUiState` / `SeatUi` / `SeatTag` 定义在 `viewmodel/room/RoomVM.kt`：状态里**不放字符串**，
  座位标签是枚举（`HOST/READY/WAITING/NONE`），由 Activity 换成 `R.string`。

## 5. 线程约定（最容易写错的地方）

| 调用 | 线程要求 |
|---|---|
| `Session.host` / `Session.join`（内部 `TcpLink.host/join`：起 socket + 等房主分身份） | **挂起**，内部在 IO 线程做阻塞调用（调用方在 `viewModelScope` 里 launch） |
| `ServiceAdvertiser` / `ServiceDiscovery` 的所有方法 | **必须主线程**（NsdManager 要 Looper）；`Session.leave()` 自己切，页面不用管 |
| `Peer.send` / `TcpLink.send` / `TcpLink.sendTo` | 任意线程（内部有写锁；链路自己不在锁内写 socket） |
| `PeerListener` 回调 / `TcpHandler` 的回调 | 在**收到消息那条连接的读线程**上按序到达（同一条连接不并发、不重入；不同连接之间可能并发） |
| `Room` 的所有方法 | 任意线程（内部一把锁）；它不发任何东西，所以锁里没有网络 |
| `LobbyChannel` 的命令处理 | 在 `synchronized(lock)` 里"改 Room + 编码快照"，**写出 socket 也在锁内**（顺序必须和版本递增一致） |
| `RoomState` 收集 | UI 在 `repeatOnLifecycle(STARTED)` 里收（`BaseActivity.bindUi`），自动主线程 |

UI 侧一律"收 `StateFlow` → 渲染"，所以**任何地方都不用自己 `runOnUiThread`**。

## 6. 页面层的几个必要细节

- **搜索只在页面可见时跑**：`BaseActivity.bindUi` 里 `startDiscovery()` + `awaitCancellation()`，
  `finally` 里 `stopDiscovery()`。NSD 没有"刷新"，重扫就是 stop + start，正好对上 STARTED 生命周期。
- **首页 = 没有连接**：`RoomsActivity.onStart` 一律 `session.leave()`。这句约定一次解决三件事：
  进房间前状态干净、回到首页一定收摊（退出/被踢/房间解散都算）、异常退出的残留也能被收掉。
  房间页反过来**什么都不关**，因为它可能只是被对局页盖住。
- **转屏不重建 Activity**（两个页面都配了 `configChanges`）：订阅不重来；连接本来也不跟页面走，
  所以转屏连"断线"这个可能性都没有了。
- **键盘**：manifest `adjustResize` + `onWindowInsetsChanged` 里把 `ime.bottom - systemBars.bottom`
  补在**最后一行**（`rowActions`）的 bottom padding 上，整列会被顶上来；
  不要直接用 `ime.bottom`（会和 `BaseActivity` 已加的系统栏内边距叠加）。
- **消息列表按服务端 `seq` 做 DiffUtil key**：连发两个相同的"好"必须是两条。
- **座位条按座位号做 key**，内容是纯函数（VM 的 `seatModels` + Activity 的 `seatItems`）：人变了就整格重画。
- **设置面板每次 `render` 都照着快照重画**：先摘监听 → 落选中态/开关 → 再挂监听，
  否则"同步"会被当成"用户点了"，命令回环。
- **本机资料要落盘**：昵称和头像是在首页定的，存在 base 的 `DSManager`（DataStore）里
  （`ProfileStore`）：头像是编码好的 WebP 转 base64，昵称用"pending 值 + 500ms 防抖"的写法——
  连续输入只写一次，写的过程中又敲字，循环会把最新值接着写掉；离开页面时把没落盘的那次立刻写掉。
  读失败一律回默认值，不能让存储坏了页面打不开。
- **点击防抖**：`setOnDebouncedClickListener`（base 提供），否则连点两下会开两个房间。
- **默认昵称带随机后缀**（`玩家%1$d`）：两台设备都留空就用同一个默认名，服务端会按设计拒绝重名。

## 7. 接一个游戏要动哪里

房间和游戏是同一套骨架，**只换命令、规则和快照里的字段**：

| 现在 | 对局版 |
|---|---|
| `LobbyProtocol.Command` + `LobbyChannel.onCommand` | 大厅换成一个新的 `azul` 频道（`AzulChannel`）：命令进 `AzulProtocol.Command`，规则调 `:core` 的引擎 |
| `RoomSnapshot.messages`（`lobby` 的快照） | 对局快照 `AzulSnapshot` 里加 `table`（工厂区 / 中央区 / 各家手牌 / 当前回合） |
| `Room.start()` 里那句"还在施工" | `phase = RoomPhase.Playing` + `clearReadyLocked()` + `publish()` |
| `RoomSnapshot.startHint` | 换成"该谁动"这类服务端算出来的状态 |
| 开局后 `takeSeat/swap/setOption/selectGame` 的 `Lobby` 检查 | 保留：对局中本来就该锁住 |

**对局频道怎么接进来**：写一个 `AzulChannel : TcpHandler`（`ns = "azul"`），对局页的 VM
在 `init` 里 `link.register(...)`、`onCleared` 里 `link.unregister("azul")`——
**链路、`lobby`、`blob` 一行都不用改**，也**绝不要关链路**（它是进程的资源）。
对局页从 `App.session.connection` 拿 `link`，所以"房间 → 对局"不需要传任何东西；
回大厅就是退了对局页，`lobby` 和 `Room` 还在原处。开局时的名册从 `connection.lobby` 的快照里取。

**规则裁决必须留在 `:core`**（将来的 `AzulEngine` / 规则状态机）。`Room` 只做
"谁的回合能发什么命令、该广播给谁"，它不判断花色/图案怎么放——这条线越了就会出现
"客户端和服务端各有一套判定"的经典 bug。

新游戏进目录只要改 `GameCatalog`：加一条 `GameInfo`（`id` / `title` / 人数范围 /
`options` 默认值），设好 `available = true`。首页和设置面板会自己跟着变。

## 8. 已验证 / 未验证

旧版聊天页在真机上验过：开房（`TcpServer` 绑定 + NSD 广播 + 回环客户端 + 报到）、
收消息回显、外来客户端（设备自带 `nc`）进房、客人页标题取服务端房名。
**这一版（`TcpLink` + 频道 + 快照协议 + 进程级连接）是全套重写的**，JVM 上有 52 个 `:app-net` 用例
（含真 socket 的链路/中转/心跳/图片/大厅往返），但**真机行为需要按第 9 节再验一遍**：

- [ ] 首页渲染 + 搜索启动
- [ ] 首页点左上角方框 → 系统相册选图 → 预览出正方形头像；选一张竖拍的照片也不躺倒
- [ ] 杀掉进程重开：昵称和头像都还在（DataStore 落盘），输入框里是上次的名字
- [ ] 「创建房间」→ 建房时状态栏显示"正在连接…"、按钮置灰；连好之后自动进房间页
- [ ] 房间里 1 人、座位条 1 号位是自己和头像、状态「房间由房主同步…」
- [ ] `nc` 直连：先发 `_link` 的 `hello` 拿到 `welcome` 里的身份，再发 `lobby` 的 `join`，之后收到 `snap`，房主页同步出现新座位
- [ ] 第二台设备（或 `nc`）进房：房主座位条上出现对方的头像；对方拉房主的头像也成功
- [ ] 房主点座位 → 换座 / 请人离开；客人的界面跟着变
- [ ] 设置里改人数 / 开关玩法 → 两边同时变，准备状态被清空
- [ ] 全员准备后房主按「开始游戏」→ 状态栏出现"对局还在施工"
- [ ] 房间页按「离开」回首页 → 对方那边那个人立刻消失（首页可见 = 一定收摊）
- [ ] 连不上时（比如对方刚退出）→ 留在首页，状态栏给出原因，不会进到一个空房间
- [ ] 客人切飞行模式（网直接没了，没有 FIN）→ 房主侧 25 秒内心跳超时，座位条上那个人消失
- [ ] 房间页开着时按 Home 再回来 → 不会掉线（连接不跟页面走）

**没验证**：两台真机之间的 NSD 发现（需要两台设备；代码路径与上述一致）。

**没做**：聊天记录不落盘、断线不重连、房主退出即解散、进程被杀后回房间页会提示并退出（不自动重连）。

## 9. 手工冒烟测试的小技巧

设备上自带 `nc`，可以直接对服务端说协议，比点 UI 快得多。
**注意先报到**：发业务帧之前必须先发 `_link` 的 `hello`，否则一律被回 `bye`（"先报到再说话"）。
身份不用自己填——房主会回一条 `welcome` 把 `p1`/`p2`… 分给你。

```bash
# 找到本应用的监听端口（0A = LISTEN）
adb shell cat /proc/net/tcp6

# 用 nc 当一个客人：报到 → 进房 → 说一句 → 准备（JSON 里带引号，所以用 printf 一条条发）
{
  printf 'FRAME|{"ns":"_link","p":{"type":"hello"}}\n'
  sleep 1
  printf 'FRAME|{"ns":"lobby","p":{"type":"join","name":"NcTester"}}\n'
  sleep 2
  printf 'FRAME|{"ns":"lobby","p":{"type":"say","text":"hi"}}\n'
  sleep 2
  printf 'FRAME|{"ns":"lobby","p":{"type":"ready","ready":true}}\n'
  sleep 3
} | nc 127.0.0.1 <端口>

# 期望输出（帧里 ns=lobby、from=null 的就是整份快照；下面是省略过的）
# FRAME|{"v":1,"ns":"_link","p":{"type":"welcome","playerId":"p2"}}                    ← 房主分给你的身份
# FRAME|{"v":1,"ns":"_link","p":{"type":"ping"}}                                     ← 每 10 秒一次，25 秒不回就掉线
# FRAME|{"v":1,"ns":"lobby","p":{"type":"snap","room":{"version":1,"roomName":"小明的房间","hostId":"p1","phase":"Lobby","gameId":"azul","tableSize":4,"startHint":"还差 1 人",...}}}
# FRAME|{..., "messages":[{"seq":1,"playerId":"p2","text":"hi"}] ...}
# FRAME|{..., "players":[{...,"id":"p2",...,"ready":true}] ...}
```

`nc` 不会回 `pong`，所以**整段脚本要短于 25 秒**，或者看到 `ping` 就手敲一条
`FRAME|{"ns":"_link","p":{"type":"pong"}}`。要不到图时回的是
`FRAME|{"ns":"blob","from":"<房主>","p":{"type":"missing","id":"…"}}`。

改协议时，先在这里跑通再动 UI：`nc` 能看到的才是真的协议。

## 10. 测试清单

```bash
./gradlew :core:test              # 行协议 + 真 socket（收发/广播/断开/坏包/房主回环）
./gradlew :app-net:test           # 帧路由/中转/心跳 + 图片频道 + 大厅频道（真 socket）+ 房间规则
./gradlew :app:assembleDebug      # 打包
./gradlew :app-net:lintDebug :app:lintDebug
```

能测的都放在不碰 Android 的类里：纯逻辑是 `Room` / `LobbyProtocol`，
联网逻辑是 `TcpLink` / `BlobChannel` / `LobbyChannel`（只依赖 `:core` + 协程，用真 socket
起在本机回环上跑）。加功能时先问一句"这段能不能写成这样的纯逻辑"，能就写在那儿。
