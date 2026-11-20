# app-net —— 局域网层（发现 + 房间）

两块东西：

1. **找到对方**：`ServiceAdvertiser` / `ServiceDiscovery`（NSD/mDNS）。
2. **对上话**：`room/` 下的房间协议、房间规则、会话（把 `:core` 的 socket 缝起来）。

不搬字节（那在 `:core`），不碰界面（那在 `:app`）。

## 为什么单独一个模块

`NsdManager` 属于 `android.net.nsd`，是 Android API。把它放进 `:core` 会让那个
"零 Android 依赖、`./gradlew :core:test` 秒跑"的内核变成 Android 模块。
真正搬字节的 socket 代码在 `:core`（纯 JVM，有集成测试），这里负责**怎么找到对方、以及房间里说什么**——
所以它是唯一能同时用协程和 Android API 的联网模块。

```
:app  ──┬─→ :base ──→ :core
        └─→ :app-net ──→ :core
```

## 发现：两个类 + 两个数据

| 类 | 干什么 | 谁用 |
|---|---|---|
| `ServiceAdvertiser` | 广播"我开了房间"（注册 NSD 服务，带上 TCP 端口） | 房主 |
| `ServiceDiscovery` | 搜"谁开了房间"，回调里给出 IP + 端口 | 想加别人房间的人 |
| `FoundHost` | 一条搜索结果：`serviceName` / `host` / `port` | 上面两个的公共数据 |
| `NSD_SERVICE_TYPE` | 服务类型串 `_ppporiagames._tcp.`，两边必须一致 | 两边 |

## 房间：协议 / 规则 / 会话

| 类 | 干什么 | 测得了吗 |
|---|---|---|
| `room/RoomProtocol` | 行协议编解码：命令（JOIN/SAY/READY/SEAT/SWAP/SIZE/GAME/OPT/KICK/START）、事件（SNAP/ERROR/BYE）、快照编解码与超预算裁剪 | ✅ JVM 单测 |
| `room/Room` | 房主侧规则：分座位、排座次、改人数、换游戏、玩法开关、准备、踢人、开局校验。**不碰 socket**（每个成员只给 `send`/`close` 回调） | ✅ JVM 单测 |
| `room/RoomSession` | 把 `TcpServer` + NSD 广播 + 回环客户端 + `Room` 缝起来，对外只有 `state` + 一串命令方法 | ❌ 要真机 |
| `room/RoomState` | UI 要渲染的东西：本机连接状态（CONNECTING/ONLINE/CLOSED）+ 服务端快照（`RoomSnapshot`） | — |

```kotlin
// 房主
val session = RoomSession.host(context, "小明的房间", "小明")
session.state.collect { render(it) }        // 主线程收，里面就有整份快照
session.say("开一局？")
session.setReady(true)
session.close()                             // 必须在主线程（要注销 NSD 广播）

// 加入
val session = RoomSession.guest(foundHost, "小红")
```

## 协议：只有快照，没有增量事件

```
客户端 → 服务端    JOIN|<playerId>|<昵称>    SAY|<内容>       READY|<0|1>
                  SEAT|<座位>               SWAP|<a>|<b>     SIZE|<人数>
                  GAME|<游戏id>             OPT|<玩法>|<0|1>  KICK|<playerId>  START

服务端 → 客户端    SNAP|<整份快照>           ERROR|<原因>      BYE|<原因>
```

两条重要的约定：

1. **`SNAP` 广播给所有人，包括发起者**。客户端不做本地回显、不做本地推演，
   所以花名册/座次/设置永远只有服务端这一份真相（旧版"客户端自己攒 JOINED/LEFT"的做法已经删了）。
2. **`playerId` 由客户端生成**。服务端认可后就是身份，座位是可变的、身份不变；
   客户端因此不用等"你被分到几号"就知道快照里哪一行是自己。

快照是一行 `键=值`，值里的 `% | ; , =` 和换行都转义（`RoomProtocol.esc/unesc`），
所以昵称/房名/聊天内容里带任何分隔符都不会切错。单行上限 8KB（`Wire`），
快照目标 6KB——**超了先丢最老的聊天**，人和设置一条不少。

## 房主：开局 + 广播

```kotlin
val session = RoomSession.host(this, "小明的房间", "小明")
// 上面一行内部做的事（不用你写）：TcpServer(0) 绑端口 → NSD 广播那个端口
//                                → Tcp.connect("127.0.0.1", 端口) 让房主自己入座
```

房主身份是**服务端认的**：`127.0.0.1` 回环连上来的那条连接在 `Room` 里带 `host` 标记，
改设置/踢人/开局都按它校验。客户端自称"我是房主"没有用。

## 加入：搜索 + 连接

```kotlin
val discovery = ServiceDiscovery(context, object : ServiceDiscovery.Listener {
    override fun onFound(room: FoundHost) = roomList.add(room)          // 直接用，别自己解析 IP
    override fun onLost(serviceName: String) = roomList.removeAll { it.serviceName == serviceName }
})

discovery.start()   // 主线程；想重扫只能 stop() 再 start()

// 用户点了某个房间之后
val session = RoomSession.guest(room, "小红")
```

房主和客人拿到的是**同一个 `RoomSession` 形状**（`state` + 命令方法 + `close`），UI 只有一份。

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
resolve 收集协程，一处是 `RoomSession` 的后台建房间/连接。**`:core` 的 socket 层刻意不引协程**：
那里 `send()` 是阻塞写，慢对端自然形成背压；换成 channel 就得回答"队列满了丢哪条消息"，
丢一条聊天/出牌消息是没法诊断的 bug。

## 必须记住的约束

1. `ServiceAdvertiser` / `ServiceDiscovery` / `RoomSession.close()` **只能在主线程调用**；
   反过来 `TcpServer.start` / `Tcp.connect` 是**阻塞**的，别在主线程调（`RoomSession` 已经放进协程）。
2. 一个进程里不要同时开两个实例广播同一个服务（`FAILURE_ALREADY_ACTIVE`）。
3. `ServiceDiscovery` / `RoomSession` 没单元测试（`NsdManager` 是 final 的 Android 类，JVM 侧测不了）。
   所以这两处的原则是**把状态压到最少**：`ServiceDiscovery` 只剩 3 个字段
   （`discoveryListener` / `inbox` / `job`，外加主线程独占的 `known`），全都能用 `stopInternal()`
   一次收干净；改这里时先数一遍字段，多一个就得说清"它为什么不能由 cancel 管"。
4. 能测的都往 `RoomProtocol` / `Room` 里放：它们不碰 socket、不碰 Android，
   `./gradlew :app-net:test` 秒跑。加功能时先想"能不能变成这两个类里的纯逻辑"。
5. **客户端不存房间状态**：`RoomSession` 里没有花名册、没有座位表，只有"最近一份快照"。
   要展示新东西，就把它加进 `RoomSnapshot`（服务端算好再推），别在客户端加推导。
