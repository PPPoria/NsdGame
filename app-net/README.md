# app-net —— 局域网层（发现 + 房间）

两块东西：

1. **找到对方**：`ServiceAdvertiser` / `ServiceDiscovery`（NSD/mDNS）。
2. **对上话**：`chat/` 下的聊天协议、房间规则、会话（把 `:core` 的 socket 缝起来）。

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
| `NSD_SERVICE_TYPE` | 服务类型串 `_nsdgames._tcp.`，两边必须一致 | 两边 |

## 房间：协议 / 规则 / 会话

| 类 | 干什么 | 测得了吗 |
|---|---|---|
| `chat/ChatProtocol` | 行协议编解码：`JOIN` / `SAY` → `WELCOME` / `JOINED` / `LEFT` / `LOG` / `ERROR` | ✅ JVM 单测 |
| `chat/ChatRoom` | 房主侧规则：分座位、拒绝重名/满员、决定广播给谁。**不碰 socket**（只收一个 `send` 回调） | ✅ JVM 单测 |
| `chat/ChatSession` | 把 `TcpServer` + NSD 广播 + 回环客户端 + `ChatRoom` 缝起来，对外只有 `state` / `say` / `close` | ❌ 要真机 |
| `chat/ChatState` | UI 要渲染的不可变状态（阶段 / 花名册 / 消息 / 提示） | — |

```kotlin
// 房主
val session = ChatSession.host(context, "小明" + "的房间", "小明")
session.state.collect { render(it) }        // 主线程收
session.say("开一局？")
session.close()                             // 必须在主线程（要注销 NSD 广播）

// 加入
val session = ChatSession.guest(foundHost, "小红")
```

协议长这样（**自由文本永远是最后一个字段**，所以内容里带 `|` 也不会切错；**只认座位不认昵称**）：

```
客户端 → 服务端    JOIN|<昵称>              SAY|<内容>
服务端 → 客户端    WELCOME|<座位>|<房名>     JOINED|<座位>|<昵称>
                   LEFT|<座位>               LOG|<座位>|<内容>
                   ERROR|<原因>
```

一条重要的约定：`LOG` 会广播给**所有人，包括发送者**。客户端不做本地回显，
所以"我发的"和"别人发的"在序列里只有一份真相，顺序不会岔。

## 房主：开局 + 广播

```kotlin
val session = ChatSession.host(this, "小明的房间", "小明")
// 上面一行内部做的事（不用你写）：TcpServer(0) 绑端口 → NSD 广播那个端口
//                                → Tcp.connect("127.0.0.1", 端口) 让房主自己入座
```

## 加入：搜索 + 连接

```kotlin
val discovery = ServiceDiscovery(context, object : ServiceDiscovery.Listener {
    override fun onFound(room: FoundHost) = roomList.add(room)          // 直接用，别自己解析 IP
    override fun onLost(serviceName: String) = roomList.removeAll { it.serviceName == serviceName }
})

discovery.start()   // 主线程；想重扫只能 stop() 再 start()

// 用户点了某个房间之后
val session = ChatSession.guest(room, "小红")
```

房主和客人拿到的是**同一个 `ChatSession` 形状**（`state` / `say` / `close`），UI 只有一份。

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
resolve 收集协程，一处是 `ChatSession` 的后台建房间/连接。**`:core` 的 socket 层刻意不引协程**：
那里 `send()` 是阻塞写，慢对端自然形成背压；换成 channel 就得回答"队列满了丢哪条消息"，
丢一条聊天/出牌消息是没法诊断的 bug。

## 必须记住的约束

1. `ServiceAdvertiser` / `ServiceDiscovery` / `ChatSession.close()` **只能在主线程调用**；
   反过来 `TcpServer.start` / `Tcp.connect` 是**阻塞**的，别在主线程调（`ChatSession` 已经放进协程）。
2. 一个进程里不要同时开两个实例广播同一个服务（`FAILURE_ALREADY_ACTIVE`）。
3. `ServiceDiscovery` / `ChatSession` 没单元测试（`NsdManager` 是 final 的 Android 类，JVM 侧测不了）。
   所以这两处的原则是**把状态压到最少**：`ServiceDiscovery` 只剩 3 个字段
   （`discoveryListener` / `inbox` / `job`，外加主线程独占的 `known`），全都能用 `stopInternal()`
   一次收干净；改这里时先数一遍字段，多一个就得说清"它为什么不能由 cancel 管"。
4. 能测的都往 `ChatProtocol` / `ChatRoom` 里放：它们不碰 socket、不碰 Android，
   `./gradlew :app-net:test` 秒跑。加功能时先想"能不能变成这两个类里的纯逻辑"。
