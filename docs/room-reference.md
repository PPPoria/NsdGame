# 游戏准备房间：完整参考样例

> 这份文档是给**下一个接手的人或 AI** 看的。代码本身是"地基 + 一个能跑通的样例"，
> 不是要扩张的框架。看完再动手；改之前先读根 `README.md` 的「架构硬约束」。
>
> 这一版把旧的"聊天室"改成了"游戏准备房间"，最关键的改动是协议：**客户端只渲染服务端快照**，
> 不再自己维护花名册。第 3 节是整个设计的心脏。

## 1. 它是什么

两个页面 + 一套房间协议，跑在局域网里：

```
手机 A（房主）                                手机 B（客人）
┌──────────────────────────────┐             ServiceDiscovery
│ TcpServer(0)  监听端口         │             ── 看到 FoundHost(ip, port)
│   ├─ 远端客人 ────────────────┼─── LAN ──── 然后 Tcp.connect(ip, port)
│   └─ 房主自己 Tcp.connect     │
│        ("127.0.0.1", port)    │
│ Room    座位/玩法/准备/踢人    │
│ ServiceAdvertiser 广播端口     │
│ RoomSession → RoomState → UI  │             RoomSession → RoomState → UI
└──────────────────────────────┘
```

**房主自己也是一条普通客户端连接**（走 `127.0.0.1` 回环），所以：
房主 UI 和客人 UI 拿到的是同一个 `Peer` 类型、同一个 `RoomState`、同一个页面代码，
会话里不存在 `if (isHost)` 的分支。

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
  room/RoomProtocol.kt   命令/事件编解码 + 快照编解码 + 转义 + 超预算裁剪（纯逻辑，可测）
  room/Room.kt           房主侧规则：座位、座次、玩法、准备、踢人、开局（不碰 socket，可测）
  room/RoomState.kt      RoomSnapshot / RoomPlayer / RoomMessage + UI 侧 RoomState
  room/RoomSession.kt    缝合层：TcpServer + 广播 + 回环客户端 + Room

app/.../ui/              只画和点
  RoomsActivity.kt       首页：昵称 / 创建房间 / 搜到的房间列表
  RoomActivity.kt        游戏准备房间（房主与客人共用）
  RoomAdapter.kt         房间列表（ListAdapter）
  SeatAdapter.kt         座位条（座位号 = 出手顺序）
  MessageAdapter.kt      聊天列表（自己的靠右、别人的靠左带昵称）
  RoomSettingsSheet.kt   底部设置面板：选游戏 / 牌桌人数 / 玩法开关
  SeatSheet.kt           点座位弹出的动作面板：入座 / 换座 / 请人离开
```

## 3. 协议：服务端权威快照

### 3.1 命令与事件

`类型|字段|字段`，一条消息一行（`:core` 的 `Wire` 保证没有换行）。

```
客户端 → 服务端
  JOIN|<playerId>|<昵称>     进房报到（playerId 由客户端生成）
  SAY|<内容>                 说话
  READY|<0|1>                准备 / 取消准备
  SEAT|<座位>                自己挪到空座位
  SWAP|<a>|<b>               房主：交换两个座位（一个是空位就是挪过去）
  SIZE|<人数>                房主：改牌桌人数
  GAME|<游戏id>              房主：换游戏
  OPT|<玩法id>|<0|1>         房主：开关自定义玩法
  KICK|<playerId>            房主：请人离开
  START                      房主：开始游戏

服务端 → 客户端
  SNAP|<整份快照>            唯一的数据通道（每次变化推给所有人，含发起者）
  ERROR|<原因>               那条命令没生效（状态没动）
  BYE|<原因>                 这条连接要被关掉（满员 / 被踢 / 房间解散）
```

### 3.2 快照（SNAP）的格式

一行 `键=值`，键是短名，值是转义过的：

```
SNAP|v=1|room=<房名>|host=<房主playerId>|phase=lobby|game=azul|size=4|hint=<还不能开局的原因，空串=能开>
    |opt=<玩法id>=<0|1>;<...>
    |p=<playerId>,<座位>,<昵称>,<h|-><r|->;<...>
    |msg=<seq>,<playerId>,<内容>;<...>
```

- `phase`：`lobby`（现在只会是这个）/ `playing`（等对局写好）；
- `hint`：服务端算好的 `startHint`——"还差 N 人" / "等 X、Y 准备" / 空串（能开）。
  客户端**只负责显示**，不自己再判一遍；
- `p` 里的标志位：`h` = 房主，`r` = 准备好了，没有就是 `-`；
- `msg` 是聊天记录，`seq` 由服务端自增（UI 靠它做列表 diff）。

**转义**（`RoomProtocol.esc/unesc`）：`% | ; , =` 和 `\r\n` 都换成 `%XX`。
于是昵称、房名、聊天内容里带任何分隔符都不会切错，也**不需要担心换行**。

**长度与裁剪**：单行有 8KB 上限（`Wire.MAX_LINE_BYTES`），快照目标是 6KB
（`RoomProtocol.MAX_SNAPSHOT_BYTES`）。超了就从**最老的聊天**开始丢，
人和设置一条不少（`RoomProtocol.snapshot` 里那个 while 循环）。Room 内存里最多留
`Room.MAX_HISTORY = 40` 条聊天。

### 3.3 三条设计决定

1. **客户端没有自己的状态**。它只有一个"最近收到的快照"（`RoomState.snapshot`）。
   发命令不改本地——`SNAP` 会广播给所有人（含发起者），等它回来界面才动。
   这样"花名册/座位/设置"永远只有服务端这一份真相，不会有"本机看起来对、对面看起来错"。
2. **`playerId` 由客户端生成**（`RoomSession` 里 `UUID` 前 16 位）。服务端认可后就是身份；
   座位是可变的（房主排座次），身份不变。客户端因此不用等"你被分到几号"就知道自己是谁。
3. **房主由服务端认**：`127.0.0.1` 回环连上来的那条连接，`RoomSession.onJoin` 给
   `Room.join(host = true)`。客户端自称"我是房主"没用。快照里的 `hostId` 只是让 UI
   知道该不该显示房主功能；真正的校验在 `Room.hostOnly()` 里。

## 4. 状态机（RoomSession）

```
RoomState { host, phase(CONNECTING|ONLINE|CLOSED), myId, snapshot?, note? }
RoomSnapshot { version, roomName, hostId, phase, gameId, tableSize, startHint,
               options, players, messages }
```

- 只有 `state: StateFlow<RoomState>` + 一串命令方法（`say` / `setReady` / `takeSeat` /
  `swapSeats` / `setTableSize` / `selectGame` / `setOption` / `kick` / `start`）+ `close()`。
  UI 不碰协议。
- `host(context, roomName, playerName)` 内部依次：`Room` → `TcpServer(0).start()`
  → 主线程 `ServiceAdvertiser.register(roomName, localPort)` → `Tcp.connect("127.0.0.1", port)`。
- `guest(foundHost, playerName)`：`Tcp.connect(foundHost.host, foundHost.port)`。
- 两条路最后都走同一个 `clientListener`：`onOpen` 发 `JOIN`，`onLine` 只认
  `SNAP` / `ERROR` / `BYE`，`onClosed` 置 `CLOSED` 并带原因。
- 建房间/连接都在内部 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 里，构造即返回；
  失败写进 `state.note`，不抛给 UI。
- `close()` **必须在主线程**（要注销 NSD 广播），并且会取消 scope；启动过程中被关掉的话，
  代码里用 `scope.isActive` 挡了"已经注册的广播"和"刚连上的 socket"，
  避免留一个挂在局域网里的房间。

## 5. 线程约定（最容易写错的地方）

| 调用 | 线程要求 |
|---|---|
| `TcpServer.start` / `Tcp.connect` | **阻塞**，别在主线程（`RoomSession` 已放进协程） |
| `ServiceAdvertiser` / `ServiceDiscovery` 的所有方法、`RoomSession.close()` | **必须主线程**（NsdManager 要 Looper） |
| `Peer.send` | 任意线程（内部有写锁） |
| `PeerListener` 回调 | 在**那条连接自己的读线程**上按序到达（同一条连接不并发、不重入） |
| `Room` 的所有方法 | 任意线程（内部一把锁），但会在锁内调成员的 `send`：**别让 `send` 变慢** |
| `RoomState` 收集 | UI 在 `repeatOnLifecycle(STARTED)` 里收（`BaseActivity.bindUi`），自动主线程 |

UI 侧一律"收 `StateFlow` → 渲染"，所以**任何地方都不用自己 `runOnUiThread`**。

## 6. 页面层的几个必要细节

- **搜索只在页面可见时跑**：`BaseActivity.bindUi` 里 `startDiscovery()` + `awaitCancellation()`，
  `finally` 里 `stopDiscovery()`。NSD 没有"刷新"，重扫就是 stop + start，正好对上 STARTED 生命周期。
- **转屏不重建 Activity**（两个页面都配了 `configChanges`）：房间页一旦重建就会
  `onDestroy → session.close()`，房主转个屏整屋人掉线。
- **键盘**：manifest `adjustResize` + `onWindowInsetsChanged` 里把 `ime.bottom - systemBars.bottom`
  补在**最后一行**（`rowActions`）的 bottom padding 上，整列会被顶上来；
  不要直接用 `ime.bottom`（会和 `BaseActivity` 已加的系统栏内边距叠加）。
- **消息列表按服务端 `seq` 做 DiffUtil key**：连发两个相同的"好"必须是两条。
- **座位条按座位号做 key**，内容是纯函数（`RoomActivity.seatItems`）：人变了就整格重画。
- **设置面板每次 `render` 都照着快照重画**：先摘监听 → 落选中态/开关 → 再挂监听，
  否则"同步"会被当成"用户点了"，命令回环。
- **点击防抖**：`setOnDebouncedClickListener`（base 提供），否则连点两下会开两个房间。
- **默认昵称带随机后缀**（`玩家%1$d`）：两台设备都留空就用同一个默认名，服务端会按设计拒绝重名。

## 7. 接一个游戏要动哪里

房间和游戏是同一套骨架，**只换命令、规则和快照里的字段**：

| 现在 | 对局版 |
|---|---|
| `Room.say()` / `setReady()` | 加上 `RoomCommand.Play/Draw/Pass` → `Room.play()` 调 `:core` 的规则引擎 |
| `RoomSnapshot.messages` | 加 `table`（局面：工厂区 / 中央区 / 各家手牌 / 当前回合） |
| `Room.start()` 里那句"还在施工" | `phase = RoomPhase.PLAYING` + `clearReadyLocked()` + 广播 |
| `RoomSnapshot.startHint` | 换成"该谁动"这类服务端算出来的状态 |
| 开局后 `takeSeat/swap/setOption/selectGame` 的 LOBBY 检查 | 保留：对局中本来就该锁住 |

**规则裁决必须留在 `:core`**（将来的 `AzulEngine` / 规则状态机）。`Room` 只做
"谁的回合能发什么命令、该广播给谁"，它不判断花色/图案怎么放——这条线越了就会出现
"客户端和服务端各有一套判定"的经典 bug。

新游戏进目录只要改 `GameCatalog`：加一条 `GameInfo`（`id` / `title` / 人数范围 /
`options` 默认值），设好 `available = true`。首页和设置面板会自己跟着变。

## 8. 已验证 / 未验证

旧版聊天页在真机上验过：开房（`TcpServer` 绑定 + NSD 广播 + 回环客户端 + 报到）、
收消息回显、外来客户端（设备自带 `nc`）进房、客人页标题取服务端房名。
**这一版（快照协议 + 游戏准备房间）的代码是新的，真机行为需要按第 9 节再验一遍**：

- [ ] 首页渲染 + 搜索启动
- [ ] 「创建房间」→ 房间里 1 人、座位条 1 号位是自己、状态「房间由房主同步…」
- [ ] `nc` 直连：`JOIN|<id>|<昵称>` 后收到 `SNAP|…`，房主页同步出现新座位
- [ ] 房主点座位 → 换座 / 请人离开；客人的界面跟着变
- [ ] 设置里改人数 / 开关玩法 → 两边同时变，准备状态被清空
- [ ] 全员准备后房主按「开始游戏」→ 状态栏出现"对局还在施工"

**没验证**：两台真机之间的 NSD 发现（需要两台设备；代码路径与上述一致）。

**没做**：聊天记录不落盘、断线不重连、房主退出即解散、进程被杀后回房间页会重开一个同名空房间。

## 9. 手工冒烟测试的小技巧

设备上自带 `nc`，可以直接对服务端说协议，比点 UI 快得多：

```bash
# 找到本应用的监听端口（0A = LISTEN）
adb shell cat /proc/net/tcp6
# 用 nc 当一个客人：报到 → 等广播 → 说一句 → 准备
adb shell "(printf 'JOIN|abcdefgh|NcTester\n'; sleep 4; printf 'SAY|hi\n'; sleep 2; printf 'READY|1\n'; sleep 3) | nc 127.0.0.1 <端口>"
# 期望输出（每条都是整份快照）
# SNAP|v=1|room=小明的房间|host=<房主id>|phase=lobby|game=azul|size=4|hint=还差 1 人|...
# SNAP|...（带自己那句 hi）
# SNAP|...（p 段里自己带 r）
```

改协议时，先在这里跑通再动 UI：`nc` 能看到的才是真的协议。

## 10. 测试清单

```bash
./gradlew :core:test              # 行协议 + 真 socket（收发/广播/断开/坏包/房主回环）
./gradlew :app-net:test           # 命令往返 + 快照往返/裁剪 + 房间规则 + 6KB 快照过真 socket
./gradlew :app:assembleDebug      # 打包
./gradlew :app-net:lintDebug :app:lintDebug
```

能测的都放在不碰 Android 的两个类里（`RoomProtocol` / `Room`）——加功能时先问一句
"这段能不能变成这两个类里的纯逻辑"，能就写在那儿。
