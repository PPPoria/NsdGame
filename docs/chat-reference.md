# 局域网聊天：完整参考样例

> 这份文档是给**下一个接手的人或 AI** 看的。代码本身是"地基 + 一个能跑通的样例"，
> 不是要扩张的框架。看完再动手；改之前先读根 `README.md` 的「架构硬约束」。
>
> 样例已经过真机验证（见文末"验证过什么"），可以放心当模板抄。

## 1. 它是什么

两个页面 + 一套房间协议，跑在局域网里：

```
手机 A（房主）                                手机 B（客人）
┌──────────────────────────────┐
│ TcpServer(0)  监听端口         │             ServiceDiscovery
│   ├─ 远端客人 ────────────────┼─── LAN ──── 看到 FoundHost(ip, port)
│   └─ 房主自己 Tcp.connect     │             然后 Tcp.connect(ip, port)
│        ("127.0.0.1", port)    │
│ ChatRoom      分座位/广播      │
│ ServiceAdvertiser 广播端口     │
│ ChatSession → ChatState → UI  │             ChatSession → ChatState → UI
└──────────────────────────────┘
```

**房主自己也是一条普通客户端连接**（走 `127.0.0.1` 回环），所以：
房主 UI 和客人 UI 拿到的是同一个 `Peer` 类型、同一个 `ChatState`、同一个页面代码，
会话里不存在 `if (isHost)` 的分支。代价是每方向几 KB 内核缓冲；换来的是**一条链路要维护**。

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

app-net/.../            可用协程 + Android API
  Nsd.kt                 服务类型串 + FoundHost(serviceName, host, port)
  ServiceAdvertiser.kt   注册 NSD 服务（带端口），注销
  ServiceDiscovery.kt    搜服务 → FoundHost；resolve 单飞队列（Channel + 挂起 for）
  chat/ChatProtocol.kt   协议编解码 + 上限（纯逻辑，可测）
  chat/ChatRoom.kt       房间规则：分座位、拒重名、广播给谁（不碰 socket，可测）
  chat/ChatState.kt      UI 要渲染的不可变状态
  chat/ChatSession.kt    缝合层：TcpServer + 广播 + 回环客户端 + ChatRoom

app/.../ui/              只画和点
  RoomsActivity.kt       首页：昵称 / 创建房间 / 搜到的房间列表
  ChatActivity.kt        聊天室（房主与客人共用）
  RoomAdapter.kt         房间列表（ListAdapter）
  MessageAdapter.kt      消息（自己的靠右、别人的靠左带昵称）
```

## 3. 协议

`类型|字段|字段`，**自由文本永远是最后一个字段**（所以内容里带 `|` 不会切错），
换行不可能出现（`Wire` 禁掉了），因此**不需要转义**。

```
客户端 → 服务端
  JOIN|<昵称>                   进房报到
  SAY|<内容>                    说话

服务端 → 客户端
  WELCOME|<座位>|<房名>          进房成功，第一条事件
  JOINED|<座位>|<昵称>           有人进来了（含自己）
  LEFT|<座位>                   有人走了
  LOG|<座位>|<内容>              一条消息（广播给所有人，含发送者）
  ERROR|<原因>                  拒绝/提示
```

三条设计决定，改协议前先想清楚：

1. **只认座位不认昵称**。昵称可能重、以后可能允许改，座位在一条连接的生命周期里稳定。
2. **`LOG` 广播给所有人，包括发送者**。客户端不做本地回显——"我发的"和"别人发的"在序列里
   只有一份真相，顺序不会岔，也不用两边各写一套追加逻辑。代价是发送方要多等一个 RTT。
3. **花名册只靠 `JOINED`/`LEFT` 维护**，服务端连"自己"的 `JOINED` 也发一遍，
   于是客户端只有一条规则：照着事件建表。

上限（`ChatProtocol`）：昵称 12、内容 300、房名 24、错误 80 字符；单行 8KB。

## 4. 状态机（`ChatSession`）

```
ChatState { host, phase(CONNECTING|ONLINE|CLOSED), roomName, mySeat, members, messages, note }
```

- 只有 `state: StateFlow<ChatState>`、`say(text)`、`close()` 三个出口；UI 不碰协议。
- `host(context, roomName, playerName)` 内部依次：`ChatRoom` → `TcpServer(0).start()`
  → 主线程 `ServiceAdvertiser.register(roomName, localPort)` → `Tcp.connect("127.0.0.1", port)`。
- `guest(foundHost, playerName)`：`Tcp.connect(foundHost.host, foundHost.port)`。
- 两条路最后都走同一个 `clientListener`：`onOpen` 发 `JOIN`，`onLine` 按事件更新 state，
  `onClosed` 置 `CLOSED` 并带原因。
- 建房间/连接都在内部 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 里，构造即返回；
  失败写进 `state.note`，不抛给 UI。
- `close()` **必须在主线程**（要注销 NSD 广播），并且会取消 scope；启动过程中被关掉的话，
  代码里用 `scope.isActive` 挡了"已经注册的广播"和"刚连上的 socket"，避免留一个挂在局域网里的房间。

## 5. 线程约定（最容易写错的地方）

| 调用 | 线程要求 |
|---|---|
| `TcpServer.start` / `Tcp.connect` | **阻塞**，别在主线程（`ChatSession` 已放进协程） |
| `ServiceAdvertiser` / `ServiceDiscovery` 的所有方法、`ChatSession.close()` | **必须主线程**（NsdManager 要 Looper） |
| `Peer.send` | 任意线程（内部有写锁） |
| `PeerListener` 回调 | 在**那条连接自己的读线程**上按序到达（同一条连接不并发、不重入） |
| `ChatState` 收集 | UI 在 `repeatOnLifecycle(STARTED)` 里收（`BaseActivity.bindUi`），自动主线程 |

UI 侧一律"收 `StateFlow` → 渲染"，所以**任何地方都不用自己 `runOnUiThread`**。

## 6. 页面层的几个必要细节

- **搜索只在页面可见时跑**：`BaseActivity.bindUi` 里 `startDiscovery()` + `awaitCancellation()`，
  `finally` 里 `stopDiscovery()`。NSD 没有"刷新"，重扫就是 stop + start，正好对上 STARTED 生命周期。
- **转屏不重建 Activity**（两个页面都配了 `configChanges`）：聊天页一旦重建就会
  `onDestroy → session.close()`，房主转个屏整屋人掉线。
- **键盘**：manifest `adjustResize` + `onWindowInsetsChanged` 里补 `ime.bottom - systemBars.bottom`，
  不要直接用 `ime.bottom`（会和 `BaseActivity` 已加的系统栏内边距叠加）。
- **消息列表用引用相等做 DiffUtil key**（`oldItem === newItem`）：连发两个相同的"好"
  必须是两个 item；按 `equals` 比较会被合并成一个。
- **点击防抖**：`setOnDebouncedClickListener`（base 提供），否则连点两下会开两个房间。
- **默认昵称带随机后缀**（`玩家%1$d`）：两台设备都留空就用同一个默认名，服务端会按设计拒绝重名。

## 7. 改造成"游戏房间"要动哪里

聊天协议和游戏协议是同一套骨架，**只换命令/事件和房间规则**：

| 现在 | 游戏版 |
|---|---|
| `ChatCommand.Say(text)` | `GameCommand.Play(cardIndex, wildColor)` / `Draw` / `Pass` |
| `ChatEvent.Log(seat, text)` | `GameEvent.Played(seat, card)` / `StateChanged(snapshot)` / `Finished(winner)` |
| `ChatRoom.say()` | `GameRoom.play()`：**调用 `:core` 的 `UnoEngine.reduce`**，非法操作抛/回 `ERROR` |
| `ChatState.messages` | `ChatState.table`（对局快照 + 手牌） |

**规则裁决（能不能出这张牌）必须留在 `:core`**（`UnoEngine` / `UnoState.isPlayable`）。
房间层只做"谁的回合能发什么命令、该广播给谁"，它不判断规则——这条线越了就会出现
"客户端和服务端各有一套判定"的经典 bug。

## 8. 已验证 / 未验证

真机（一台手机，通过 `adb` + `uiautomator` 读界面树逐步确认）：

- 首页渲染、搜索启动；
- 「创建房间」→ `房主 · 在线` + `1 人在房间里：<昵称>`（TcpServer 绑定 + NSD 广播 + 回环客户端 + JOIN/WELCOME 全通）；
- 发送消息 → 自己的气泡出现（服务端回显路径）；
- 用设备自带的 `nc` 当**外来客户端**直连服务端：收到 `WELCOME|2|…`、花名册 `JOINED`、`LOG` 回显，
  房主页同步出现该客人的气泡、花名册变 2 人，断开后回落 1 人；
- 客人页面（同机临时 `exported=true` 起来验的）：标题取服务端给的房名、`2 人在房间里`、状态是"在线"而非"房主"。

**没验证**：两台真机之间的 NSD 发现（需要两台设备；代码路径与上述一致）。

**没做**：聊天记录不落盘、断线不重连、房主退出即解散、进程被杀后回到聊天页会按 Intent 重开一个同名空房间。

## 9. 手工冒烟测试的小技巧

设备上自带 `nc`，可以直接对服务端说协议，比点 UI 快得多：

```bash
# 找到本应用的监听端口（0A = LISTEN，最后一列前是 uid）
adb shell cat /proc/net/tcp6
# 用 nc 当一个客人：报到 → 等广播 → 说一句
adb shell "(printf 'JOIN|NcTester\n'; sleep 4; printf 'SAY|hi\n'; sleep 3) | nc 127.0.0.1 <端口>"
# 期望输出
# WELCOME|2|<房名>
# JOINED|1|<房主昵称>
# JOINED|2|NcTester
# LOG|2|hi
```

## 10. 测试清单

```bash
./gradlew :core:test              # 34 个：行协议 + 真 socket（收发/广播/断开/坏包/房主回环）
./gradlew :app-net:test           # 13 个：协议往返 + 房间规则（分座位/重名/满员/离开）
./gradlew :app:assembleDebug      # 打包
./gradlew :app-net:lintDebug :app:lintDebug
```

能测的都放在不碰 Android 的两个类里（`ChatProtocol` / `ChatRoom`）——加功能时先问一句
"这段能不能变成这两个类里的纯逻辑"，能就写在那儿。
