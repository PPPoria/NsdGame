# app-net —— 局域网发现（NSD）

**只做一件事**：告诉局域网"我开了个牌局"，以及"局域网里谁开了牌局"。
不碰 socket、不碰规则、不碰界面。

## 为什么单独一个模块

`NsdManager` 属于 `android.net.nsd`，是 Android API。把它放进 `:core` 会让那个
"零 Android 依赖、`./gradlew :core:test` 秒跑"的内核变成 Android 模块。
真正搬字节的 socket 代码在 `:core`（纯 JVM，有集成测试），这里只负责**怎么找到对方**。

```
:app  ──┬─→ :base ──→ :core
        └─→ :app-net ──→ :core
```

## 对外就两个类

| 类 | 干什么 | 谁用 |
|---|---|---|
| `ServiceAdvertiser` | 广播"我开了牌局"（注册 NSD 服务，带上 TCP 端口） | 房主 |
| `ServiceDiscovery` | 搜"谁开了牌局"，回调里给出 IP + 端口 | 想加别人房间的人 |
| `FoundHost` | 一条搜索结果：`serviceName` / `host` / `port` | 上面两个的公共数据 |
| `NSD_SERVICE_TYPE` | 服务类型串 `_nsdgames._tcp.`，两边必须一致 | 两边 |

## 房主：开局 + 广播

```kotlin
val session = HostSession()                            // :core 里的会话（还没写，见根 README）

// 远端客人从这进来
val server = TcpServer(0, session).start()             // 端口传 0 让系统分配

// 房主自己也是一条客户端连接：走 127.0.0.1 回环，和客人同一个函数、同一个 Peer 类型
val me = Tcp.connect("127.0.0.1", server.localPort, session.hostPlayer)   // 别在主线程调

val advertiser = ServiceAdvertiser(context, object : ServiceAdvertiser.Listener {
    override fun onRegistered(serviceName: String, port: Int) = showRoomName(serviceName)
    override fun onFailed(reason: String) = toast(reason)
})
advertiser.register("茯苓的牌桌", server.localPort)   // ← 必须是主线程

// 退出
advertiser.unregister()
server.stop()
```

## 加入：搜索 + 连接

```kotlin
val discovery = ServiceDiscovery(context, object : ServiceDiscovery.Listener {
    override fun onFound(room: FoundHost) = roomList.add(room)          // 直接用，别自己解析 IP
    override fun onLost(serviceName: String) = roomList.removeAll { it.serviceName == serviceName }
})

discovery.start()   // 主线程；想重扫只能 stop() 再 start()

// 用户点了某个房间之后（connect 是阻塞的，放后台线程/线程池）
val peer: Peer = Tcp.connect(room.host, room.port, guestSession)   // 之后就用这条 Peer 收发
```

房主和客人的 UI 用的是**同一个 `PeerListener` 接口 + 同一个 `Peer` 类型**，所以会话/UI 只有一份。

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

本模块用 `kotlinx-coroutines-android`（本来就在允许列表里，`:app` 也早就带着它），
仅用于上面那个收集协程。**`:core` 的 socket 层刻意不引协程**：那里 `send()` 是阻塞写，
慢对端自然形成背压；换成 channel 就得回答"队列满了丢哪条消息"，而 6 人回合制游戏里
丢掉一条出牌消息是没法诊断的 bug。

## 必须记住的约束

1. `ServiceAdvertiser` / `ServiceDiscovery` 的方法**都只能在主线程调用**，别的方法（`connect`、`send`、
   `TcpServer.start`）相反——**不能**在主线程调。
2. 一个进程里不要同时开两个实例广播同一个服务（`FAILURE_ALREADY_ACTIVE`）。
3. 没有单元测试：`NsdManager` 是 final 的 Android 类，JVM 侧测不了。所以这条模块的原则是
   **把状态压到最少**：`ServiceDiscovery` 只剩 3 个字段（`discoveryListener` / `inbox` / `job`，
   外加主线程独占的 `known`），全都能用 `stopInternal()` 一次收干净；改这里时先数一遍字段，
   多一个就得说清"它为什么不能由 cancel 管"。
