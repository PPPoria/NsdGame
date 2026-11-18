# NsdGames · 网络分层架构设计

> **地位**：本项目局域网多人游戏通信底座的权威架构文档。`docs/ROLES_AND_COLLABORATION.md` 中的 `ADR-00x`、`架构设计 §x.y` 锚点全部指向本文档。
> **所有者**：架构设计师（兼集成协调）。**变更方式**：只能通过 ADR。
> **状态**：`v1 Draft` —— 决策已定，接口签名待评审冻结（见 §11）。

---

## 0. 已冻结的输入决策

来自需求澄清（Q1~Q8），后续设计不得推翻，只能走 ADR 变更：

| # | 决策 | 对架构的直接约束 |
|---|---|---|
| D1 | UI 用 **View + viewBinding**，复用 `BaseActivity`/`BaseFragment` | UI 层拿到的是 `StateFlow` + `Channel`，不是 Compose State |
| D2 | **2~6 人**，开局前可进可退，开局后座位冻结 | 座位表是房主独占资源；`PLAYING` 期不允许增删 |
| D3 | **短时保座** 30~60s，凭 `sessionToken` 重连并补发快照 | 房主必须随时能产出可序列化全量状态 |
| D4 | TCP + 应用层 **PING/PONG**，5s 间隔，30s 无响应判掉线踢出 | 超时判定必须走单调时钟，且可注入以便单测 |
| D5 | **增量事件 + seq**，客端用同一份 `GameLogic` 推演 | 插件必须纯函数；必须配 `SYNC_HASH` 兜底 + 快照锚点 |
| D6 | 房间 **4~6 位口令**，明文传输，防作弊只做房主校验 | 口令校验在房主；不做 per-player 视图（见 §11 开放问题） |
| D7 | 测试靠 **2 台真机 + JVM 假客户端** | TCP/协议/会话层必须能在纯 JVM 上跑 |
| D8 | 扩展性优先保 **加新游戏容易** | `GameLogic` 插件化做到底；传输抽象够用即可 |

**D5 × D6 的硬冲突（必须知道）**：客端要本地推演就必须持有完整状态 → 隐藏手牌对所有人可见 → 牌类游戏存在天然作弊面。**本文档按"公开信息博弈"（五子棋/象棋/跳棋/黑白棋）设计**。牌类需先升 ADR 改协议为 per-player 视图，见 §11。

---

## 1. 领域模型

### 1.1 核心实体

| 实体 | 归属模块 | 职责 | 权威性 |
|---|---|---|---|
| `PlayerId` | `:net-proto`（值） | 玩家身份，值存 `DSManager`，跨房间稳定 | 客端自报，房主登记 |
| `RoomInstanceId` | `:net-proto`（值） | "这一次建房"的 UUID，用于识别房间重建 | 房主生成 |
| `RoomDescriptor` | `:net-proto`（值） | 不可变值对象：房间元信息 + 地址候选 | 房主广播 |
| `Endpoint` | `:net-core`（transport） | 连接目标抽象（不是 `String`，为 BLE/WFD 预留） | — |
| `Connection` | `:net-core`（transport） | 一条已分帧的消息通道 | 无游戏语义 |
| `Peer` | `:net-core`（session） | `Connection` + `PlayerId` + 握手结果 + `lastSeen` | 房主 |
| `Seat` / `Seats` | `:game-api` | 座位号 ↔ `PlayerId`，`PLAYING` 期冻结 | **房主唯一** |
| `GameState S` | `:game-<id>` | 规则状态，不透明泛型 | **房主唯一** |
| `GameEvent E` | `:game-<id>` | 已校验的状态变更，带全局 `seq` | **房主唯一** |
| `GameCommand C` | `:game-<id>` | 玩家意图，**不是**状态变更 | 无权威 |
| `EventLog` | `:net-core`（session） | 环形缓冲（256 条）+ 可落盘 | 房主 |
| `Snapshot` | `:net-core`（session） | `S` 序列化 + 对应 `seq` + `stateHash` | 房主 |
| `SessionToken` | `:net-core`（session） | 重连凭证，绑 `PlayerId` + 座位 + 过期时间 | 房主签发 |

### 1.2 权威状态位置（明确到进程）

- **房主进程**：`GameState`、`EventLog`、`Seats`、`SessionToken` 表、`Peer` 表、口令。**无任何客端可写字段。**
- **客端进程**：`GameState` 的副本（由事件折叠，可能陈旧）、会话状态机、`lastAppliedSeq`。**不是权威。**
- **ViewModel**：只持 `PlayerView`（渲染投影）与 UI 状态。**不持 socket，不持 `GameState` 引用。**

---

## 2. 分层架构与接口签名

### 2.1 模块切分

核心决策：**把 TCP 分帧、协议编解码、会话状态机全部放进 Kotlin JVM 模块**。`java.net` 在纯 JVM 可用，因此 80% 的高风险逻辑（半包、去重、超时、重连）能在秒级单测里覆盖，延续 `:core` 的传统（D7）。

```
:core         (kotlin-jvm, 不动)      时间/颜色/哈希
:base         (android-lib, 不动)     UI 基类 / DSManager / ThreadPool
:game-api     (kotlin-jvm, 新建)      GamePlugin 契约，零网络零 Android
:net-proto    (kotlin-jvm, 新建)      帧格式 / Envelope / RoomDescriptor / 载荷 schema
:net-core     (kotlin-jvm, 新建)      transport + session + room
:net-android  (android-lib, 新建)     NsdManager / 前台服务 / 权限
:game-gomoku  (kotlin-jvm, 新建)      第一个插件
:app          (android-app)           UI / ViewModel / ServiceLocator
```

依赖方向（严格单向，编译期强制）：

```
:app → :net-android → :net-core → :net-proto → (coroutines-core, serialization-json)
:app → :game-gomoku → :game-api
:net-core → :game-api          ← 会话层驱动插件，插件看不见传输
:app → :base → :core
```

**模块（构建单元）归架构师；包（归属单元）按角色表分配。** 同一模块内的多个包可以分属不同角色，`build.gradle.kts` 冻结在架构师手里（详见 ADR-009）。

需在 `libs.versions.toml` 新增：`kotlinx-coroutines-core`、`kotlinx-coroutines-test`（同 1.9.0）。

### 2.2 发现层

```kotlin
// :net-proto —— 发现 / 手输IP / 二维码 共用同一个值对象
@Serializable
data class RoomDescriptor(
    val protoVersion: Int,            // 协议大版本，不同则不可连
    val gameId: String,               // "gomoku"
    val gameVersion: String,          // "1.2.0"，插件语义版本
    val roomInstanceId: String,       // 房主进程 UUID
    val roomName: String,
    val hostNick: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val phase: RoomPhase,             // Lobby / Playing
    val hasPasscode: Boolean,
    val addresses: List<HostAddress>  // 多网卡候选，可能为空
)

@Serializable data class HostAddress(val ip: String, val port: Int)

sealed interface DiscoveredRoom {
    val serviceName: String                              // NSD 服务名，去重键
    data class Found(override val serviceName: String) : DiscoveredRoom
    data class Ready(override val serviceName: String, val desc: RoomDescriptor) : DiscoveredRoom
    data class Lost(override val serviceName: String) : DiscoveredRoom
}

interface RoomDiscovery {
    /** 前台驱动：进页 subscribe，退后台 cancel。实现内部串行化 resolve。 */
    fun discover(gameId: String?): Flow<DiscoveredRoom>
    suspend fun resolve(serviceName: String): RoomDescriptor?
}

interface RoomAdvertiser {
    /** port 必须是已 listen 的真实端口。close() == unregisterService。 */
    suspend fun advertise(desc: RoomDescriptor, port: Int): Advertisement
}

interface Advertisement {
    suspend fun update(desc: RoomDescriptor)
    suspend fun close()
}
```

**为什么返回 `Flow<DiscoveredRoom>` 而不是 `Flow<List<...>>`**：NSD 的 resolve 是串行的（一次只能一个在飞），事件流让 UI 立刻显示"发现 3 个房间（2 个解析中）"，而不是等全部解析完。UI 侧用 `StateFlow<List<...>>` 兜住即可。

### 2.3 传输层

**对"Transport 产出 ByteArray 流"的修正**：契约上移到**完整消息**。半包责任留在 Transport 内，否则两层都能坏、两层都要测。

```kotlin
typealias Message = ByteArray   // 一条完整消息 = 一整帧（9 字节帧头 + JSON 载荷），见 ADR-014

sealed interface Endpoint {     // 为 BLE / Wi-Fi Direct 预留，不要用 host:port 字符串
    data class Tcp(val host: String, val port: Int) : Endpoint
}

interface Transport {
    val scheme: String                       // "tcp"，用于日志与降级策略
    suspend fun listen(port: Int = 0): Listener
    suspend fun connect(endpoint: Endpoint, timeoutMs: Long): Connection
}

interface Listener {
    val localPort: Int                       // port=0 时为系统分配的实际端口
    val incoming: Flow<Connection>
    suspend fun close()
}

interface Connection {
    val peerLabel: String                    // 仅日志用途
    val incoming: Flow<Message>              // 已按帧切好，永不丢半包
    val closed: Flow<CloseReason>
    suspend fun send(msg: Message)           // 挂起直到写完（背压）
    suspend fun close(reason: CloseReason)
}

sealed interface CloseReason {
    data object Eof : CloseReason
    data object RemoteClosed : CloseReason
    data class Io(val cause: Throwable) : CloseReason
    data class Protocol(val message: String) : CloseReason   // 帧非法/超长
    data object LocalClose : CloseReason
}
```

`TcpTransport` 实现必查项（ADR-008）：

| 项 | 要求 | 理由 |
|---|---|---|
| 端口 | `listen(0)` 让系统分配 | 避开固定端口被占 / 被防火墙拦的整类问题 |
| 并发模型 | 每连接两个协程（读循环 + 写 Channel） | 6 人 = 12 个阻塞协程，无压力 |
| 调度器 | 新建专属 `newFixedThreadPool(8)`，线程名 `nsdnet-N` | **不要用 `:base` 的 `ThreadPool`**：其 `AbortPolicy` + 队列 64 会抛 `RejectedExecutionException` 直接炸掉一次发送 |
| 读超时 | `Socket.soTimeout` | 心跳层配合 |
| 写超时 | `withTimeoutOrNull` 包住 `send` | 防对端不消费导致挂死 |
| 帧长上限 | 256 KiB，超限 `Protocol` 异常并断开 | 防对端恶意/错乱导致 OOM |

### 2.4 协议层

```kotlin
@Serializable
data class Envelope(
    val type: MsgType,
    val protoVersion: Int,
    val payload: JsonElement,   // 不透明：传输层不解释内容
    val seq: Long = 0,          // EVENT 用：房主单调递增
    val cmdSeq: Long = 0        // CMD 用：每玩家单调递增，幂等去重
)

interface FrameCodec {
    fun encode(env: Envelope): Message
    suspend fun readFrame(input: InputStream): Envelope          // 半包状态机在这里
    fun decodePayload(env: Envelope): Any                        // 按 type + 版本挑具体类
}
```

`payload: JsonElement` 是**关键接缝**：传输角色只保证"这段 JSON 完整送达"，游戏协议设计师只保证"这段 JSON 有意义"。两边不需要开会即可并行。

### 2.5 会话层

**对"GameHost / GameGuest 并列"的修正**：先抽 `RoomSession` 公共骨架。成员表、心跳、编解码、超时在两端都不能有差异——任何一处不对称都会变成"房主能踢人而客端踢不掉"这类 bug。

```kotlin
abstract class RoomSession(protected val scope: CoroutineScope) {
    protected val peers = mutableMapOf<PlayerId, Peer>()
    abstract val state: StateFlow<SessionState>
    abstract val uiEvents: Flow<SessionEvent>       // Channel(BUFFERED)，一次性事件
    protected suspend fun sendTo(p: PlayerId, env: Envelope)
    protected suspend fun broadcast(env: Envelope)
    protected fun startHeartbeat()                  // 双向 PING/PONG，5s
}

class HostSession(
    private val transport: Transport,
    private val plugin: GamePlugin<*, *, *>,
    private val clock: TimeSource,                  // 注入！否则超时逻辑只能靠 sleep 测
    private val scope: CoroutineScope
) : RoomSession(scope) {

    val roomDescriptor: StateFlow<RoomDescriptor>   // 供 advertise 消费
    val hostView: StateFlow<HostView>               // 权威状态 + 座位表

    suspend fun openRoom(cfg: RoomConfig, passcode: String?)
    suspend fun startGame(config: JsonElement)      // 人数不足抛 IllegalState
    suspend fun kick(playerId: PlayerId, reason: KickReason)
    suspend fun closeRoom(reason: RoomCloseReason)
    /** 收 CMD → 校验 → 产 EVENT → reduce → seq++ → 广播。房主本地操作也走这条路径。 */
    suspend fun dispatch(from: PlayerId, cmd: GameCommand): CommandResult
}

class GuestSession(
    private val transport: Transport,
    private val pluginId: GamePluginId,             // 客端也需要规则类做本地推演（D5 的后果）
    private val clock: TimeSource,
    private val scope: CoroutineScope
) : RoomSession(scope) {

    val room: StateFlow<GuestRoomState>             // 镜像 + 我的座位 + lastAppliedSeq
    suspend fun join(endpoint: Endpoint, passcode: String?, nickname: String): JoinOutcome
    suspend fun reconnect(token: SessionToken): JoinOutcome
    suspend fun send(cmd: GameCommand)              // 乐观 UI：先本地预演，等 EVENT 修正
    suspend fun leave()
}
```

### 2.6 游戏插件（`:game-api`）

```kotlin
interface GamePlugin<S : Any, E : GameEvent, C : GameCommand> {
    val id: GamePluginId                 // "gomoku"
    val version: String                  // "1.2.0"，参与握手
    val minPlayers: Int
    val maxPlayers: Int
    val stateSerializer: KSerializer<S>  // 快照必需（D3 的直接后果）

    /** 纯函数：唯一允许改状态的地方。禁读时间、禁无种子随机、禁依赖 Map 迭代顺序。 */
    fun reduce(state: S, event: E): S

    /** 命令校验 → 0..n 个事件；Left 表示非法。只有房主调用。 */
    fun validate(state: S, actor: Seat, cmd: C): Either<RejectReason, List<E>>

    /** 客端投影。MVP 直接 return state；牌类时在这里裁掉别人的手牌。 */
    fun viewFor(state: S, viewer: Seat?): Any = state

    fun canStart(seats: Seats): Boolean = seats.occupied in minPlayers..maxPlayers

    /** 保座期玩家不可用时是否产生事件（如超时自动弃权）。默认不产生。 */
    fun onPlayerUnavailable(state: S, seat: Int): List<E> = emptyList()

    /** 漂移检测。默认 JSON 规范化后 hash；插件可覆盖以提速。 */
    fun hash(state: S): String
}
```

**为什么 `reduce` 必须纯**：客端推演 + 事件回放 + 断线补发 + 存档全押在这一个性质上。一次 `Random().nextInt()` 直接写进 `reduce`，重连补发就会漂移，且表现为"偶尔不同步"——最难调的一类 bug。**随机必须在 `validate` 时决定并写入 `Event`**（用注入的 `SeededRandom`）。

### 2.7 UI 层（`:app`，沿用 View 体系，D1）

```kotlin
// 房间列表：发现是短期 UI 驱动 ← 正好落在 base 的 bindUi() 语义上
class RoomListActivity : BaseActivity<ActivityRoomListBinding>() {
    private val vm: RoomListViewModel by viewModels()
    override suspend fun bindUi() { vm.rooms.collect { render(it) } }   // 退后台自动 cancel → 自动 stopDiscovery
}

class TableViewModel : ViewModel() {
    // socket 与房间状态在进程级单例里，VM 只拿句柄
    private val handle: RoomHandle = LanRuntime.currentRoom ?: error("进入对局页但房间已关闭")
    val table: StateFlow<TableView>
    val events: Flow<TableEvent>       // Channel → 不会重复消费（符合 base 契约 §5.2）
}
```

---

## 3. 状态机与时序

### 3.1 房主：房间状态

```
IDLE ──openRoom()──▶ ADVERTISED ──startGame()──▶ PLAYING ──gameOver()──▶ FINISHED
  ▲                    │  ▲                        │                     │
  │                    │  └──────回到 lobby────────┘                     │
  └────closeRoom()─────┴─────────────closeRoom()──────────────────────────┘
```

### 3.2 房主：每连接状态（5 态）

```
CONNECTED ──HELLO 合法──▶ HANDSHAKING ──JOIN_REQ 通过──▶ ACTIVE
    │                          │                            │
    │              VERSION_MISMATCH / 满员 / 已开局 / 口令错   │ 心跳 30s 无 PONG
    │                          ▼                            ▼
    │                    REJECTED(→close)              SUSPENDED(保座 60s)
    │                                                       │      │
    │                                     RESUME(token) ────┘      │ token 过期/主动 LEAVE
    │                                                              ▼
    └── socket 异常 ──▶ DROPPED（无 token 可救）                 DROPPED
```

`SUSPENDED` 是**只读**态：保座期该 socket 已死，房主不能向他广播、不能等他操作。若轮到他出牌，由 `GamePlugin.onPlayerUnavailable` 决定（默认：挂起等待）。

### 3.3 客端状态

```
IDLE ──join()──▶ CONNECTING ──ok──▶ HANDSHAKING ──WELCOME──▶ LOBBY
  │                  │                   │                    │
  │            超时/拒绝          VERSION_MISMATCH      GAME_STARTED
  │                  ▼                   ▼                    ▼
  └──────────▶ FAILED          INCOMPATIBLE_VERSIONS       PLAYING
                                                             │  │
                                         心跳超时 / IO 异常 ──┘  │ GAME_OVER
                                               ▼                ▼
                                         RECONNECTING         FINISHED
                                               │
                                 token ok ─────┴──── token 过期 ──▶ HOST_LOST
                                               ▼
                                    LOBBY / PLAYING（快照补发后恢复）
```

### 3.4 关键时序

**① 加入**

```
Guest                                      Host
  │ discover(NSD) → Ready(desc)             │
  │ 版本本地预检（protoVersion/gameId/gameVersion）  ← UI 层就过滤不兼容房间
  │──── TCP connect ───────────────────────▶│ accept → CONNECTED
  │──── HELLO{gameId, gameVersion, playerId, nick, deviceModel} ─▶│ 校验版本
  │◀─── WELCOME{room: RoomDescriptor, sessionId} ───────────────│ 加入前快照，仅展示/预检
  │──── JOIN_REQ{passcode, nick} ──────────▶│ 校验口令 / 座位 / 阶段
  │◀─── JOIN_OK{sessionToken, mySeat} ──────│ ← **纯私有凭证**，不含名册（ADR-018）
  │◀─── SNAPSHOT{seq, state, room, players[]} ─│ ← **名册/座位/设置的唯一权威来源**
```

**第三趟握手由两帧组成**（私有凭证 + 权威快照），两帧来自房主的**同一次状态读取**，所以不可能互相矛盾。`JOIN_OK` 之所以不能并入 `SNAPSHOT`：`sessionToken` 是**私有**信息，塞进广播快照等于把每个人的重连凭证发给所有人。

**名册、座位分配、游戏设置一律只从 `SNAPSHOT` 读**（ADR-017 / ADR-018）。`WELCOME.room` 与 `JOIN_OK.mySeat` 都是"非权威副本"，`PROTOCOL.md` 必须标注，UI 不得据它们渲染名册。

口令由**房主明文比对**（D6）。客端不缓存旧口令重试。

**② 开局**

```
Host UI ─ startGame(config) ─▶ HostSession
   │ canStart(seats) == false → 直接回 UI 错误，不发网络包
   │ 生成 SNAPSHOT{seq = 下一个值, state, room(phase=Playing), players[]}
   │   —— 不新增 GAME_STARTED 类型，见 ADR-016（终局同理：SNAPSHOT{phase=Finished}）
   │   —— **seq 不保证等于 1**：大厅期变更也推进同一个计数器（ADR-017 补充）
   │   —— 客端判定开局只能看 room.phase，**禁止**用 seq 猜
   │ 本地 reduce（房主自己的 UI 与客端走同一条码路径，避免双实现）
   └ broadcast → 所有 ACTIVE peer；SUSPENDED 记为 pendingResume，重连时补 SNAPSHOT
```

**③ 出牌 / 落子**

```
Guest.send(cmd)                        Host.dispatch(from, cmd)
   │ cmdSeq = n（每玩家独立单调）              │
   │─── CMD{cmdSeq=n, payload} ─────────▶│ n <= lastCmdSeq[from] → 丢弃 + 回 CMD_RESULT(duplicate)
   │   （UI 先乐观预演）                    │ validate(state, seat, cmd)
   │                                      │  ├ Left(reason) → CMD_RESULT{n, rejected}  ← 仅该玩家
   │                                      │  └ Right(events) → reduce 逐个，seq++，写 EventLog
   │◀── EVENT_BATCH{seq=41..43} ──────────┤ broadcast（含房主自己）
   │◀── CMD_RESULT{n, accepted} ──────────┤ 仅发给提交者
   │  客端按 seq 落地；发现断层 → RESUME_REQ{fromSeq}
```

**④ 断线（保座 60s，D3）**

```
1. 任一侧连续 6 次（30s）无 PONG，或 IO 异常
2a. 客端 → RECONNECTING，指数退避 1s/2s/4s/8s（上限 60s）
2b. 房主 → SUSPENDED，广播 SNAPSHOT{players[] 中该 seat 的 online=false}；不动座位、不产生游戏事件
3. 重连成功 → 校验 token → ACTIVE → 发 SNAPSHOT{seq, state, room, players[]}
   （不重放事件窗口：重连时状态可能已大幅前进，全量快照更省事且不漏）
4. 60s 过期 → DROPPED → 广播 SNAPSHOT{players[] 不再包含该 seat} → 交给插件决定
```

> 原稿此处写的是 `PLAYER_OFFLINE{seat}` / `PLAYER_LEFT{seat}` —— 这两个类型在 `MsgType` 里**从未存在**，与 `GAME_STARTED` 属于同一类错误（时序图假设了不存在的类型）。已按 ADR-017 统一为「一切状态变更走 `SNAPSHOT`」。

**⑤ 房主退出（三种语义必须区分）**

| 用户动作 | 系统行为 | 客端表现 |
|---|---|---|
| 按 Home 键 | 无操作，FGS 继续 | 无感（FGS 的全部意义） |
| 点"退出房间" | `closeRoom(HOST_EXIT)` → 广播 `BYE` → 停 FGS | 收到 BYE → `HOST_LOST`，提示"房主已解散" |
| 划掉任务卡 / 进程被杀 | 无 BYE，socket 直接断 | 心跳 30s 内发现 → `HOST_LOST`。**文案必须是"与房主失去连接"，不能是"房主退出了"** |

**不要用 `START_STICKY`**：复活出来的 Service 无 socket、无状态、无房间，唯一效果是在 NSD 上留一个幽灵房间。

---

## 4. 消息协议

### 4.1 帧格式

```
 0      1      2      3      4      5        6        7        8      9    ...
+------+------+------+------+------+--------+--------+--------+------+-------------+
| 'N'  | 'G'  |proto | type |flags |      payloadLen: u32 BE       | payload     |
| 0x4E | 0x47 | u8   | u8   | u8   |     (4 字节大端，含 payload)    | JSON UTF-8  |
+------+------+------+------+------+--------+--------+--------+------+-------------+
    magic(2B)           └────────── header = 9B 固定 ──────────────┘
```

| 字段 | 决策 | 理由 |
|---|---|---|
| `magic` | 必须有 | 区分"连上了但对面不是本协议"（手输 IP 输错），比等 JSON 解析失败友好 |
| `payloadLen` 上限 | 256 KiB，超限即断开 | 无长度校验时一个坏包能让 6 个连接同时吃内存 |
| `type` | `u8` 枚举 | 省字节；棋牌每秒 < 10 条消息 |
| `payload` | **JSON**（kotlinx-serialization） | 调试期肉眼可读、抓包可读——小团队迭代最值钱的属性 |
| `flags` | 全 0，预留压缩/加密/分片位 | 避免将来改帧头 |

### 4.2 消息类型

| 方向 | type | 载荷要点 |
|---|---|---|
| C→H | `HELLO` | gameId, gameVersion, playerId, nick, deviceModel（**不重复 `protoVersion`**：帧头已有） |
| H→C | `WELCOME` | `room: RoomDescriptor`（加入前快照，**非权威**）+ sessionId |
| H→C | `REJECT` | `RejectCode`(VERSION_MISMATCH / ROOM_FULL / IN_PLAY / PASSCODE_WRONG / BANNED) + reason（**致命**：随后断开） |
| C→H | `JOIN_REQ` | passcode, nick |
| H→C | `JOIN_OK` | sessionToken, mySeat（**纯私有凭证**；名册权威来源是 SNAPSHOT，见 ADR-018） |
| H→C | `SNAPSHOT` | seq, stateHash, state, room, players[]（**权威状态的唯一来源**，见 ADR-017） |
| C→H | `CMD` | cmdSeq, payload（游戏命令，`PLAYING` 期有效） |
| H→C | `CMD_RESULT` | cmdSeq, accepted, rejectReason?（软拒绝，**不断连**） |
| H→C | `EVENT_BATCH` | fromSeq, events[seq, payload]（≤16 条/批） |
| C→H | `RESUME_REQ` | token, fromSeq |
| H→C | `SYNC_HASH` | seq, stateHash（每 ~5s 或每 10 事件） |
| C→H | `LOBBY_REQ` | reqSeq, op(`ChangeSeat` \| `Leave`), seat?（**大厅期唯一意图信道**，见 ADR-019） |
| H→C | `LOBBY_RESULT` | reqSeq, op, ok, reason?（软拒绝，**不断连**） |
| 双向 | `PING` / `PONG` | 无载荷 |
| H→C | `KICK` / `BYE` | reason |
| 双向 | `ERROR` | code, message（**致命**：随后断开） |

> **两条硬约束**：① 只有 `SNAPSHOT` 是权威状态源，其余消息携带的 `room`/`mySeat` 都是标注 `not-authoritative` 的展示副本；② "软拒绝"（`CMD_RESULT` / `LOBBY_RESULT` 的 `ok=false`）与"致命拒绝"（`REJECT` / `ERROR`）是两类语义，**不允许混用**——把"座位被占了"塞进 `REJECT` 会让客端被迫断线。

### 4.3 序列号 / ACK 判定

| 机制 | 要不要 | 理由 |
|---|---|---|
| EVENT 全局 `seq` | **必须要** | 客端靠它检测断层 + 定位 `RESUME_REQ`；也是快照锚点 |
| CMD 每玩家 `cmdSeq` | **必须要** | 幂等去重，消灭双击/重发导致的重复操作（10 行代码换掉一整类幽灵操作） |
| 逐条 ACK + 重传 | **不要** | TCP 已保证有序可靠；重传会把"重发送达 vs 玩家真点两次"变成新问题 |
| `SYNC_HASH` 周期校验 | **要**（~20 行） | D5 推演模型的唯一兜底。不一致 → 客端主动拉快照。**省了它，不一致只能靠玩家报"我这棋盘怎么不一样"** |
| 房主事件 ring buffer | **要**（256） | 支持小断层快速补发，避免每次抖动都传全量快照 |

### 4.4 版本兼容（三层，各管一件事）

| 层 | 字段 | 策略 |
|---|---|---|
| 帧/消息集合 | `protoVersion`（帧头 u8 + HELLO） | **只支持相同大版本**，不等即 `REJECT(VERSION_MISMATCH)`。局域网场景同一 APK 或相邻版本，不需要兼容矩阵 |
| 规则一致性 | `gameId + gameVersion` | major/minor 不同即拒绝，patch 允许。**规则不同绝不允许开局**（D5 下必然漂移） |
| 快照反序列化 | `stateSchemaVersion` | JSON `ignoreUnknownKeys=true` + 字段默认值做前向兼容；**删字段/改类型必须升版本**并给明确错误，而不是 `SerializationException` 糊在脸上 |

**TXT 记录必须在发现阶段就带这些字段**，让房间列表能显示"[版本不兼容]"，而不是让用户点三次都失败：

```
# "_nsdgame._tcp." / serviceName "poria-gomoku-a3f2"
txt: proto=1, game=gomoku, gver=1.2.0, inst=<uuid>, name=张三的房间,
     cur=3, max=6, phase=Lobby, pc=1, ip=192.168.1.7, port=41234
```

`ip` 是**兜底项**：Android 上 `resolveService` 返回的 `host` 在不少设备上是 `.local` 主机名或不可直接用。策略：**先 TXT 的 `ip`，再 resolve 的 host，再手输 IP**。`RoomDescriptor.addresses` 就是候选列表，`Transport.connect` 逐个试。

---

## 5. 生命周期方案

### 5.1 三件事，三种归属

| 能力 | 归属 | 启动 | 停止 |
|---|---|---|---|
| 发现（客端） | Activity/Fragment（`bindUi`） | 进入房间列表页 | 退后台（`repeatOnLifecycle(STARTED)` 自动 cancel） |
| 注册广播 + 监听（房主） | **前台服务** | 用户点"创建房间"（必在前台，规避 Android 12+ 限制） | 用户退出房间 |
| 会话维持 | 房主在 FGS；客端在进程级单例 | 建房 / 加入 | 退出 / 心跳超时 |

### 5.2 前台服务的关键决策（ADR-003）

**状态放进程级单例，不放 Service。** 理由：状态放 Service 里，Activity 必须 `bindService`（异步、`onServiceConnected` 前 UI 空白、`unbind` 时机错了就泄漏）；放进程级单例 + Service 只负责保活/通知/平台回调，Activity 进出零成本。代价：单例无系统生命周期约束，必须自己保证 `closeRoom()` 一定被调用（Service `onDestroy` 兜底）。

```kotlin
object LanRuntime {                       // 手写 ServiceLocator，与项目"不引 DI"的现状一致
    @Volatile var currentRoom: RoomHandle? = null
}
```

清单（`targetSdk 36` 必须声明 FGS type，否则启动即崩）：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service android:name=".net.RoomForegroundService"
         android:exported="false"
         android:foregroundServiceType="dataSync" />
```

### 5.3 进程被杀后的行为（明确接受损失）

| 场景 | 行为 | 用户看到 |
|---|---|---|
| 房主 Home / 切应用 | FGS 维持，连接不断 | 无感 |
| 房主锁屏 | FGS 维持（对局页建议 `keepScreenOn`） | 无感 |
| 房主划掉任务卡 | `START_NOT_STICKY`，NSD 注册失效，socket 由内核关闭 | 客端 30s 内 `HOST_LOST` |
| 客端被杀 | socket 断，房主进入 `SUSPENDED` 60s | 房主"离线中"，60s 后掉座 |
| 客端切后台很久 | 厂商策略差异大，可能冻结协程/断 socket | 走 `RECONNECTING`，保座内恢复 |

### 5.4 NSD 生命周期

`registerService` 必须在 socket **已 listen 且端口已知**之后调用（否则广播了连不上）；`unregisterService` 必须在 `closeRoom` 里显式调用——主动注销能让其他设备立刻收到 `lost`，与"等系统回收"的体验差异明显。

---

## 6. 备选方案对比

### 6.1 同步模型（ADR-004）

| 维度 | A1 全量快照广播 | A2 纯增量事件 + 客端推演 | **A3 事件 + 快照锚点（采纳）** |
|---|---|---|---|
| 带宽（五子棋一局） | ~200 KB | ~5 KB | ~15 KB |
| 客端需要规则代码 | 不需要（纯渲染） | 需要（且必须确定性） | 需要 |
| 隐藏信息（牌类） | 支持 | 不支持 | 支持（未实现） |
| 断线重连 | 极简（发快照） | 必须额外做快照 | 简单（快照锚点） |
| 漂移风险 | 零 | **高**（静默不同步） | 低（`SYNC_HASH` 兜底） |
| 调试 / 回放 | 需录像快照序列 | **事件日志即回放** | 事件日志即回放 |
| 乐观 UI（落子手感） | 难（等往返） | **天然支持** | 天然支持 |

**采纳 A3，本质是 A2 + 两个补丁**：① `SNAPSHOT` 能力必须有（D3 已强制，不是可选优化）；② `SYNC_HASH` 周期校验必须有。

**触发退回 A1 的条件（任一满足即升 ADR）**：① 第一个游戏是牌类（隐藏信息）；② 发现自己在 `reduce` 里写时间或随机；③ 玩家反馈"两边不一致"超过 2 次。

### 6.2 会话状态归属（ADR-003）

| | B1 状态放 Service | **B2 单例 + Service 保活（采纳）** |
|---|---|---|
| Activity 接入 | 必须 `bindService`，异步，UI 有一帧空白 | 直接取，同步 |
| 泄漏风险 | 低（系统管生命周期） | 中（依赖自己 close） |
| 测试难度 | 需 `ServiceTestRule` / 真机 | 纯 Kotlin 单测 |

### 6.3 传输线程模型

| | **C1 阻塞 IO + 每连接 2 协程（采纳）** | C2 NIO Selector 单线程 | C3 OkHttp WebSocket |
|---|---|---|---|
| 代码量 / 出错率 | 低 / 低 | 高 / 高（半包状态机自己写） | 中 / 低 |
| 6 人并发 | 12 个阻塞协程，无压力 | 过度设计 | 无压力 |
| 代价 | 线程数线性增长（上限 ~14，可忽略） | 调试极难 | 引入 HTTP 升级语义，未来换蓝牙反而不通用 |

### 6.4 加入路径（不是二选一，是三层降级）

1. **主路径**：NSD 自动发现
2. **兜底**：手动输入 `IP:端口`（**必须有**，这是唯一能自证"是 NSD 问题还是网络问题"的手段）
3. **UI 糖**：二维码 = `IP:port + 口令 + protoVersion` 的编码，复用路径 2 的代码

---

## 7. 风险清单

| # | 风险 | 触发条件 | 缓解 | 级别 |
|---|---|---|---|---|
| 1 | **`resolveService` 串行**，并发调用返回 `FAILURE_ALREADY_ACTIVE` | 列表页同时发现 3+ 房间 | 发现层内部 `Channel` 串行队列 + 已解析去重 + 失败退避重试 2 次。**实现难度最高的一块** | 🔴 |
| 2 | **`resolve` 的 `host` 不可用**（`.local` 主机名或 null） | 部分 ROM / 路由器 | TXT 冗余 `ip`，多候选依次试，仍失败给手输 IP | 🔴 |
| 3 | **AP 隔离 / 客户端隔离** | 公共 Wi-Fi、部分路由默认开 | 客户端无法解决。**UI 必须明说**"若连不上请检查 AP 隔离/访客网络"。这是"App 有 bug"错觉的头号来源 | 🔴 |
| 4 | **插件 `reduce` 非确定性** → 静默漂移 | 用了 `HashMap` 迭代 / 时间 / 随机 | `SYNC_HASH` + 重放两次比对 hash 的单测 + R4 grep 规则 | 🔴 |
| 5 | **Android 12+ FGS 后台启动限制** | 任何"后台自启开房"设计 | 所有建房入口都在前台 UI | 🟠 |
| 6 | **厂商 ROM 杀后台**（MIUI/EMUI/ColorOS） | 房主切后台 5~10 分钟 | FGS + 常驻通知 + 电池优化白名单提示。**M3 必须专项测**，这是"隔一会儿就掉线"最常见的根因 | 🟠 |
| 7 | **多网卡广播错误 IP**（Wi-Fi + 蜂窝 + VPN + 热点） | 开 VPN 的用户 | TXT 放**所有**非回环 IPv4 候选，客端逐个试（每候选 1.5s 超时）。**别用 `NetworkInterface` 猜哪个是 Wi-Fi** | 🟠 |
| 8 | **`POST_NOTIFICATIONS` 未授权** → FGS 通知不可见（服务仍在跑） | Android 13+ | 首次建房时申请；拒绝则降级提示，不崩溃 | 🟡 |
| 9 | **内存**：事件日志/快照无界增长 | 长局 | EventLog ring 256；快照按需生成不缓存；帧长上限 256 KiB | 🟠 |
| 10 | **`NsdManager` 重复 discover 行为差异** | 用户快速切页面 | `callbackFlow` + `awaitClose { stopServiceDiscovery }`，保证同时只有一个 discovery；200ms 退避 | 🟠 |
| 11 | **时钟跳变**影响心跳判定 | 手动改时间 / NTP 校时 | 心跳与超时一律 `SystemClock.elapsedRealtime()`，**不用 wall clock** | 🟠 |
| 12 | **BLE/WFD 扩展时地址抽象失效** | 未来扩展 | `Endpoint` 用 sealed interface（已做） | 🟡 |
| 13 | **`dataSync` FGS 的 6h/24h 限制**（API 35+，**需验证**） | 超长局 | 监控；必要时改 `connectedDevice` | 🟡 |
| 14 | **Android 16 本地网络保护（LNP）**（**需验证**，`targetSdk 36`） | Android 16 设备 | M3 用 Android 16 真机专项验证；预留 `RoomPermissionGate` 抽象 | 🟡 |
| 15 | **`MulticastLock` 是否必要**（**需验证**） | 特定 ROM 收不到 mDNS | 先不加；若"能发现但 resolve 一直失败"再试 | 🟡 |

---

## 8. MVP 拆分

原则：**先可测，再可玩**。M0~M1 完全不依赖 UI 与 NSD，一个人一台电脑即可推进。

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M0 协议与帧** | `:net-proto` 帧编解码 + Envelope + 版本字段；`:net-core` 阻塞 IO Transport | ① 任意长度 payload（1B~200KB）round-trip 100% 一致；② **对一条消息的字节序列在每个偏移量处切分**喂给 `readFrame`，结果与直接解析一致（覆盖全部半包组合）；③ 帧长超 256 KiB 抛 `Protocol` 且不断开其它连接；④ 表头 1 字节错乱立即拒绝；⑤ 1000 条乱序发送，接收端顺序 100% 正确 |
| **M1 JVM 双端会话** | `HostSession`/`GuestSession` + 握手 + 心跳 + 踢人 + `FakeTransport` | ① `:net-core:test` 全绿且 < 15s；② 真实 `127.0.0.1:0` socket 跑 6 个 guest 同时加入；③ 注入假 `TimeSource` 快进 30s → 断线判定触发（不真 sleep）；④ 口令错 → `REJECT(PASSCODE_WRONG)` 且连接关闭；⑤ 满员 → `REJECT(ROOM_FULL)` |
| **M2 NSD + 手动 IP** | `:net-android` advertiser/discovery + TXT + 串行 resolve | ① 真机 A 建房，真机 B 列表 3s 内出现；② 连续发现 3 个房间不出现 `FAILURE_ALREADY_ACTIVE`；③ A 退房后 B 列表 5s 内消失；④ 关 A 的 Wi-Fi → B 30s 内提示失去连接；⑤ 手输 `IP:端口` 能连上 |
| **M3 前台服务** | FGS + 通知 + 权限 + 生命周期 | ① 房主 Home 键 5 分钟不断线；② 锁屏 10 分钟不断线；③ 划掉任务卡 → 客端 30s 内明确提示；④ 未授权通知时有降级提示不崩溃；⑤ Android 16 真机验证 NSD+TCP 未被拦 |
| **M4 第一个插件** | `:game-gomoku`（公开信息，天然适配事件模型） | ① 完整一局；② 非法落子只让该玩家收到 `CMD_RESULT(rejected)`，其他人无感；③ 同一 `cmdSeq` 发两次只执行一次；④ 插件 100% 纯 Kotlin 单测覆盖胜负判定 |
| **M5 重连补位** | `SUSPENDED` + token + SNAPSHOT | ① 飞航 5s 恢复 → 60s 内回原座位且状态一致；② 超 60s 掉座并提示；③ 断线期间房主出牌，重连拿到的是**最新**快照而非旧事件重放 |
| **M6 打磨** | 房间列表 UI、口令 UI、踢人、漂移告警、日志落盘 | ① `SYNC_HASH` 不一致能被单测覆盖（人为造漂移）；② 事件日志可落盘并单机回放出一致状态；③ 全部单测绿 + 两模块 lint 无新告警（按 base 契约 §8 自检） |

**不放进 MVP**：观战、开局后中途加入、跨进程持久化重连、TLS、iOS/Web、Wi-Fi Direct、二维码（M6 可顺手加）。

---

## 9. 目录结构与测试策略

### 9.1 目录

```
NsdGames/
├── core/  base/                      (不动)
├── game-api/          kotlin-jvm     插件契约，零网络零 Android
│   └── src/main/kotlin/com/poria/game/api/
│       ├── GamePlugin.kt  SeededRandom.kt
│       └── model/        Seat/Seats/PlayerMeta/RejectReason
├── net-proto/         kotlin-jvm
│   └── src/main/kotlin/com/poria/net/proto/
│       ├── frame/        FrameHeader.kt  FrameCodec.kt        ← 传输角色
│       ├── Envelope.kt   MsgType.kt                           ← 传输角色
│       ├── session/      Hello/Welcome/Join*/Ping/Bye 载荷      ← 传输角色
│       ├── game/         Cmd/EventBatch/Snapshot 载荷 schema    ← 游戏协议角色
│       └── room/         RoomDescriptor.kt  HostAddress.kt     ← 架构师
├── net-core/          kotlin-jvm     ★ 最值钱的模块
│   └── src/main/kotlin/com/poria/net/core/
│       ├── transport/    Transport.kt TcpTransport.kt FakeTransport.kt   ← 传输角色
│       ├── session/      RoomSession/HostSession/GuestSession/
│       │                 SessionState/EventLog/Timeout.kt                ← 游戏协议角色
│       └── room/         RoomDiscovery.kt RoomAdvertiser.kt RoomCode.kt  ← 发现角色
├── net-android/       android-library
│   └── src/main/kotlin/com/poria/net/android/
│       ├── nsd/          NsdAdvertiserImpl.kt NsdDiscoveryImpl.kt NsdTypes.kt  ← 发现角色
│       ├── service/      RoomForegroundService.kt RoomNotification.kt          ← 发现角色
│       └── perm/         NetPermissions.kt
├── game-gomoku/       kotlin-jvm      ← 数据流开发
├── app/src/main/java/com/ppporia/nsdgames/
│   ├── App.kt  MainActivity.kt        (现有)
│   ├── LanRuntime.kt                  ← 架构师
│   └── ui/{lobby,table,join}/         ← UI 设计师(State/Event) + 交互开发(实现)
├── docs/  ARCHITECTURE.md  ROLES_AND_COLLABORATION.md  BASE_MODULE_GUIDE.md
├── gradle/libs.versions.toml          (+ coroutines-core / coroutines-test)
└── settings.gradle.kts                (+ 5 个 include)
```

**包归属 ≠ 模块归属**：同一模块内不同包可属不同角色；`build.gradle.kts` 全部冻结在架构师手里（ADR-009）。

### 9.2 测试策略（四层）

| 层 | 内容 | 目标 |
|---|---|---|
| `:net-proto` 单测 | round-trip 1B/9B/256KB/256KB+1；**暴力切分**（`for cut in 0..size` 分两段喂）；粘包连续 N 帧；错 magic/type 抛明确异常 | < 2s，每次提交都跑 |
| `:net-core` 单测 | `FakeTransport` 驱动全流程不碰 socket；真实 `127.0.0.1:0` socket（端口自动分配，永不冲突、可并行）；**注入 `TimeSource`** 做超时（`advance(30s)` 而非 `delay(30_000)`）；事件重放两次 hash 相等；`cmdSeq` 去重 / `seq` 断层 / `SYNC_HASH` 不一致 | < 15s |
| `:game-*` 单测 | 非法操作清单逐条断言 `Left`；确定性重放；`stateSerializer` round-trip | < 1s |
| 真机集成 | `NsdManager` 无可信 shadow（Robolectric 价值低，**别投入**）：把 NSD 调用面收成 3 方法 interface，业务逻辑（TXT 组装、串行队列、状态合并）全在可单测的类里，只有那 3 个方法需真机验证 | 手工 checklist |

**双真机 checklist**（建议落成 `docs/QA_CHECKLIST.md`）：建房→发现→加入→开局→终局；房主 Home 5 分钟 / 锁屏 10 分钟；客端飞航 5s/20s/90s 三档重连；房主杀进程；两人同时建房；关 Wi-Fi 再开（IP 变大概率连不回，**确认 UI 表现合理**）；公共 Wi-Fi（预期失败，确认提示清楚）；一台开 VPN（验证多候选地址）。

**JVM 假客户端**（成本 ~1 天，回报极高）：`:net-core` 的 `test` 或 `tools/fake-client` 提供 `--join 192.168.1.7:41234 --passcode 1234 --nick JVM`。不依赖 NSD、不依赖 Android，是**没有第二台手机时唯一能验证"是不是我的 App 有问题"**的手段，也是回归测试的起点。

---

## 10. 假设与待验证

以下三条决定 M2/M3 工时，**请在 M0 阶段就实测掉**：

| # | 假设 | 验证方式 | 影响 |
|---|---|---|---|
| A1 | `dataSync` 类型 FGS 在 API 35+ 无实际时长限制 | Android 15/16 真机长局 | 若受限需换 `connectedDevice` 或缩短单局 |
| A2 | `targetSdk 36` 下本地网络访问无新增授权（LNP 未强制） | Android 16 真机跑 NSD + TCP | 若强制需加权限申请门 + UI 引导 |
| A3 | `resolveService` 在目标机型返回的 `host` 是可用 IP | 真机打印 `NsdServiceInfo.host` | 决定 TXT `ip` 兜底是"优化"还是"必需" |

---

## 11. 开放问题（阻塞项）

| # | 问题 | 阻塞角色 | 需要的裁决 |
|---|---|---|---|
| 1 | 第一个游戏插件选型：棋类 or 牌类 | 游戏协议设计师、数据流开发 | 牌类需立即升 ADR 改协议为 per-player 视图（`viewFor` 已有钩子，但 `EVENT_BATCH` 需改为按玩家裁剪） |
| 2 | §2 全部接口签名是否冻结为 `FROZEN` | 全部实现角色 | 架构师评审通过后，角色表 §3 矩阵对应行升 `FROZEN`，其他角色方可并行开工 |
| 3 | `:net-core` 是否拆成 `:net-transport` + `:net-session` | 传输角色、游戏协议角色 | 单模块（当前方案，靠包级归属）vs 拆模块（边界变编译期事实）。见 ADR-009 备选 |

**裁决记录（项目所有者，2026-11-18）：① 第一个游戏为棋类；② §2 接口签名冻结为 `FROZEN`；③ `:net-core` 保持单模块。**

---

## 12. 接口冻结 v1.0

### 12.1 冻结范围

| 层 | 冻结产物 | 级别 |
|---|---|---|
| 发现 | `RoomDescriptor` / `HostAddress` / `RoomPhase` / `DiscoveredRoom` / `RoomDiscovery` / `RoomAdvertiser` / `Advertisement` | FROZEN |
| 传输 | `Message` / `Endpoint` / `Transport` / `Listener` / `Connection` / `CloseReason` | FROZEN |
| 协议 | `Envelope` / `MsgType` / `FrameCodec` / 帧头常量（magic、9B 头、256KiB 上限） | FROZEN |
| 会话 | `RoomSession` / `HostSession` / `GuestSession` / `SessionState` / `Peer` / `EventLog` / `SessionToken` | FROZEN |
| 插件 | `GamePlugin` / `GameEvent` / `GameCommand` / `Seat` / `Seats` / `PlayerMeta` / `RejectReason` / `SeededRandom` / `ValidateResult` | FROZEN |
| 运行时 | `LanRuntime` | FROZEN |

**有意不冻结的两类**（冻结它们等于冻结产品）：

1. **每种消息的载荷字段**（`CMD` / `EVENT_BATCH` / `SNAPSHOT` / `SYNC_HASH` 的具体 schema）。外壳 `Envelope` 冻结，内容由游戏协议设计师在 M4 之前补齐——因为载荷要跟着棋类规则长，提前冻结只会写错。
2. **每页的 `UiState` / `UiEvent`**（`DRAFT`），随页面演进。

### 12.2 冻结时定稿的两条签名修正

§2 的草案里有两处含糊，冻结为 FROZEN 前必须钉死，否则第一个实现角色就会各自理解：

**ADR-011 · 自研 `ValidateResult`，不引入 Arrow**

```kotlin
// :game-api
sealed interface ValidateResult<out E : GameEvent> {
    data class Accept(val events: List<E>) : ValidateResult<Nothing>
    data class Reject(val reason: RejectReason) : ValidateResult<Nothing>
}

// GamePlugin 中的签名随之定稿
fun validate(state: S, actor: Seat, cmd: C): ValidateResult<E>
```

理由：为一个 `Either` 引入整个函数式库不划算；sealed interface 的 `when` 已经能穷尽分支，且不污染 `:game-*` 的依赖面。代价：没有 `flatMap` 一类的组合子——但命令校验本来就只有"接受/拒绝"两态，用不上。

**ADR-012 · `GuestSession` 依赖插件实例，不是插件 ID**

```kotlin
class GuestSession(
    private val transport: Transport,
    private val plugin: GamePlugin<*, *, *>,   // 原草案是 GamePluginId
    private val clock: TimeSource,
    private val scope: CoroutineScope
) : RoomSession(scope)
```

理由：D5 选的是"客端用同一份 `GameLogic` 推演"，只给一个 id 客端无法本地预演。这条同时解释了为什么 `:app` 必须依赖 `:game-*`（它要 new 出插件实例）。

### 12.3 M0 交付评审修订（ADR-013 / ADR-014）

评审对象：`:net-proto` 帧层 + `:net-core/transport`（M0）。结论：**通过，P0 一项须在 M1 前修复**。
本轮两条修订都指向**我自己的文档/ADR**，不是实现者越界。

**ADR-013 · 传输线程池由常量改为派生量（修订 ADR-008）**

- **事实**：ADR-008 把 `poolSize` 钉成常量 8，但「一连接一阻塞读线程」是硬约束——房主 6 人 = 5 条客端连接 + 1 个 accept 协程，而写协程与读协程共用同一 dispatcher，峰值 7~11 线程即被抽干。
- **为什么是 P0**：症状不是「线程不够」，而是 `写超时 10000ms`，属于最难诊断的一类；且 M1 的验收标准恰好是「6 个 guest 同时加入」，会直接撞上。
- **定稿**：`poolSize` 改为 `max(8, sessionsMaxPlayers + 4)`，或直接用 `Executors.newCachedThreadPool`（线程数天然被会话层 `maxPlayers = 6` 限住，不需要再加人工上限）。**真正的并发上界是会话层的 maxPlayers，不是线程池参数**——把上界放在线程池是对错误的层设限。

**ADR-014 · `Message` = 整帧（含 9 字节帧头）**

§2.3 原注释写的是「payload，不含长度头」，**是错的**，已按实现修正。理由：传输层必须读到帧头里的 `payloadLen` 才能切帧（ADR-001 把半包责任放在 Transport 内），交出不含头的字节等于把切帧能力交给上层，直接违背 ADR-001。

**ADR-015 · 线缆投影与领域模型分离；否决 `players: List<JsonElement>`**

传输角色在 M1 前提出：`WELCOME.roomMeta` 与 `JOIN_OK.players[]/phase` 需要 `RoomPhase` / `PlayerMeta`，而 `:net-proto` 按 R1 不能依赖 `:game-api`，因此建议 `phase` 用字符串常量、`players` 用 `List<JsonElement>`、schema 全交给游戏协议设计师。

**裁决：`phase` 用 `RoomPhase` enum，`players` 用 `List<RoomPlayer>`，两者都放 `:net-proto/room/`，归架构师所有。否决 `List<JsonElement>`。**

三条理由，按重要性排序：

1. **`PROTOCOL.md` 的完整性就是它的全部价值。** 那个文件存在的意义是「一个不读 Kotlin 的实现者只靠它就完成互通」——这正是未来 iOS/Web 客户端、以及"传输角色换人"时的唯一保障。`List<JsonElement>` 把不完整性搬进线缆，`PROTOCOL.md` 只能写「服务器返回的东西你们自己猜」，等于把已经花掉的文档成本作废。
2. **名册是会话概念，不是游戏概念。** `JOIN_OK`（类型码 1..5 区间）本身就是会话握手；「谁坐在第几号位、昵称是什么、在不在线」与五子棋规则无关，任何游戏都一样。所以它不是"游戏载荷"的边界模糊，而是本来就该归会话/架构层。
3. **领域模型与线缆投影必须分开，且分开后更安全。** `Seat` / `PlayerMeta` 带规则含义（谁该行动、谁被托管），会随游戏演进；`RoomPlayer` 只描述"线缆上要说什么"。两者由**会话层在一处映射**，游戏插件永远看不到 `RoomPlayer`，proto 永远看不到 `Seat`。这正好落实了 R2「插件不碰网络」的另一半——插件也不碰线缆类型。

配套硬规则（写进 `PROTOCOL.md` 的约束）：

- **`JsonElement` 只允许出现在游戏载荷**（`CMD` / `CMD_RESULT` / `EVENT_BATCH` / `SNAPSHOT.state`），因为那些 schema 本来就要跟着规则演进——这是**有意的接缝**。
- **握手与会话消息（`HELLO`..`JOIN_OK`、`PING`/`PONG`、`KICK`/`BYE`/`ERROR`）的每个字段必须有具体类型**，不允许 opaque。
- 线缆用裸 `Int` 座位号，`Seat` 不上线缆；上线的名册统一用 `RoomPlayer`。
- `RoomPhase` 的 `wireValue` 同时被 NSD TXT 与 JSON 使用——**单一真相**，否则会出现「列表显示 Lobby、加入时被拒为 InPlay」这类极难复现的不一致。

**解锁效果**：`WELCOME` / `JOIN_OK` 现在只依赖 `:net-proto/room/`，**不依赖 `:game-api`**。传输角色的 M1 因此不再等游戏协议设计师与数据流开发。

**ADR-016 · 开局/终局不新增消息类型，复用 `SNAPSHOT`**

审 M1 前置时发现的不一致：§3.4 的时序图写了 `GAME_STARTED{config, seats[], initialSnapshot}`，但 `MsgType` 的 16 个类型里**没有** `GAME_STARTED` / `GAME_OVER`。

**裁决：不新增类型。开局 = 广播 `SNAPSHOT{seq = 1, state, room(phase = Playing), players}`；终局 = `SNAPSHOT{room.phase = Finished}`。**

理由：

1. **少一种类型 = 少一条时序分支 = 少一类竞态。** 有了独立的 `GAME_STARTED`，就必须回答「客端先收到 `SNAPSHOT`、后收到 `GAME_STARTED` 怎么办」「`GAME_STARTED` 丢了但 `SNAPSHOT` 到了怎么办」——而 `SNAPSHOT` 本身已经携带 `seq` 与 `phase`，这两问自动消失。
2. **`MsgType` 已冻结，新增类型要升 `protoVersion`。** 为一个能被 `SNAPSHOT` 完整表达的语义付一次版本号代价，不划算。
3. **与 ADR-004 一致**：状态的整体跃迁本来就该落在一个快照锚点上；只有"增量"才走 `EVENT_BATCH`。

因此 `MsgType` 保持 16 个不变，`protoVersion` 不升。§3.4 ② 已按此修正。

**ADR-017 · 大厅状态变更复用 `SNAPSHOT`（采纳传输角色的方案 A）**

大厅期这些事原先**没有任何信道**：换座、有人上线/离线、有人退出、房主改设置、被踢的其他人无感知。而 §3.4④ 又假设了 `PLAYER_OFFLINE` / `PLAYER_LEFT` 两个**从未存在**的类型——与 `GAME_STARTED` 是同一类错误（时序图假设了不存在的消息）。

**裁决：一切大厅状态变更 → 广播 `SNAPSHOT{seq++, room, players[], state}`，客端整体替换。** 与 ADR-016 同构，`MsgType` 不因这条而增加。

四条语义（传输角色要求的钉死项，均采纳）：

1. **大厅快照与对局快照共用同一个 `seq` 计数器**，客端只维护一个 `lastAppliedSeq`。否则开局瞬间会出现"两个序号空间"的边界 bug。
2. **大厅期 `state` 允许为空对象 `{}`**：由游戏协议设计师在 `SNAPSHOT` schema 里允许"尚未开局、无规则状态"。
3. **`players[]` 在大厅快照里就是权威名册**（含 `online` / `host`），客端不得本地增删。
4. **大厅期变更用快照；对局期不做名册变更**——D2 已规定 `Playing` 冻结座位，这条自动成立。反过来讲：对局中每次换座都广播全量棋盘是不可接受的，而 D2 让它不可能发生。

否决 B（新增 `ROOM_UPDATE`）：为省几百字节付一次版本断代，并多出一条"状态可能不一致"的路径。否决 C（广播 `JOIN_OK`）：`mySeat` 是私人视角字段，广播出去语义就错了。

**ADR-018 · `JOIN_OK` 降级为私有凭证；权威状态单一来源**

项目所有者指出：`JOIN_OK` 里带座位号不合适。**采纳。**

- `JOIN_OK{sessionToken, mySeat}`，**删除 `players[]` 与 `room`**。
- 理由：`JOIN_OK` 是**一刻**，名册是**持续**。把持续状态塞进一刻消息，UI 必然按一次性渲染 → 之后换座、掉线、被踢全部漏掉。这是"UI 持有第二份权威状态"最常见的成因，而 ROLES §2.6 明令禁止。
- **为什么不能把 `JOIN_OK` 整个并进 `SNAPSHOT`**：`sessionToken` 是**私有**凭证，放进广播快照等于把每个人的重连凭证发给所有人。所以第三趟握手必须是两帧（私有 ack + 权威快照），由房主**同一次状态读取**生成，因此不可能互相矛盾。
- **为什么 `mySeat` 保留**：① 私有、零歧义；② 若靠 `playerId` 在 `players[]` 里找自己，会被"房主规范化了昵称/playerId"这类边界打破，且失败是静默的（"为什么我不在名册里"）；③ 它被明确规定为**冗余副本**，权威来源仍是 `SNAPSHOT.players`。
- **通用原则（写进 `PROTOCOL.md`）**：**权威状态只有一个来源 = `SNAPSHOT`。** 其它消息只允许携带 (a) 私有信息，(b) 明确标注 `not-authoritative` 的展示副本。`WELCOME.room` 属于 (b)：仅用于"加入前展示 + 版本预检"。

**ADR-019 · 新增 `LOBBY_REQ(17)` / `LOBBY_RESULT(18)`；`protoVersion` 1 → 2**

问题：**意图与状态必须分开**（项目所有者原话）。而客端在大厅期原先**没有任何意图信道**：`CMD` 是游戏命令（`PLAYING` 才有意义，schema 归游戏协议设计师），`JOIN_REQ` 只管加入。于是"我想坐 0 号位"无处表达。

为什么这不是锦上添花：**五子棋 0 号位 = 执黑 = 先手。** 两人都想执黑时，现有唯一手段是"退出重进"，而重进会重新分配座位——等于没解。

定稿：

| 类型 | 码 | 载荷 |
|---|---|---|
| `LOBBY_REQ` | 17 | `reqSeq: Int`、`op: ChangeSeat \| Leave`、`seat: Int?`（仅 ChangeSeat） |
| `LOBBY_RESULT` | 18 | `reqSeq: Int`、`op`、`ok: Boolean`、`reason: String?` |

- **`reqSeq` 只做关联，不做去重**：`ChangeSeat` / `Leave` 天然幂等，不需要 `CMD` 那套幂等去重。这反过来证明 `cmdSeq` 只对**非幂等**的游戏命令必要。
- **软拒绝必须回执**：`REJECT` / `ERROR` 都是"随后断开"的致命语义，不能用来表达"该座位已被占"。所以 `LOBBY_RESULT` 不是冗余类型，是必需的第二半。
- **`protoVersion` 从 1 升到 2**：改类型表就必须升版本（这是传输角色自己写的规则）。现在没有任何外部实现，升版本零成本；**偷偷加类型而不升版本，才是真正昂贵的错误**——它会让"必须升版本"从规则退化成建议，等到真有外部客户端时才集中爆炸。
- 归属不变：两个类型都在 `:net-proto/session/`，传输角色所有、架构师审批。`PROTOCOL.md` 需同步这两行与"权威来源"原则。

**ADR-017 补充 · `seq` 计数器的推论（采纳传输角色的交叉推导）**

传输角色落 ADR-017 时指出：ADR-016 写的 `SNAPSHOT{seq=1, ...}` 只是**初始值**；既然大厅期变更也推进同一个 `seq`，开局快照的 `seq` 未必等于 1。**正确，我 §3.4② 的写法是错的，已修。** 由此显式定死三条：

1. **客端禁止用 `seq` 判定任何语义**，尤其"是不是开局"。判定依据只有 `room.phase`。写 `if (snapshot.seq == 1L) 这是开局` 是这条最可能出的错。
2. **`lastAppliedSeq` 只能由 `SNAPSHOT.seq` 初始化**：不得假设它从 0/1 开始，也不得假设它连续（重连时拿到的快照可能是很大的值）。
3. **`seq` 是会话级单调量，不是回合数、不是事务序号。** 任何把 `seq` 当"第 n 步"用的做法，都会在换座之后整体错位。

**ADR-020 · 线缆上必须区分"驱动 UI 的字段"与"诊断字段"**

传输角色为 `LobbyResult.reason` 钉了个样例 `"座位 0 已被 张三 占据"`，并在 KDoc 里要求 UI **必须展示 reason**。这一点要拦下来。

**裁决：凡是驱动 UI 表现的字段，线缆上必须是枚举；纯诊断字段可保留自由字符串，但必须在 KDoc 标注 `diagnostic-only`（禁止用于 UI 分支）。**

| 消息 | 现状 | 定稿 |
|---|---|---|
| `LOBBY_RESULT` | `reason: String?`（驱动 UI） | `code: LobbyRejectCode` + `detail: String?`（diagnostic-only） |
| `KICK` | `reason: String?`（驱动 UI） | `code: KickReason` + `detail?` |
| `BYE` | `reason: String?`（驱动 UI） | `code: ByeReason` + `detail?` |
| `REJECT` | `code: RejectCode` ✅ + `reason: String?` | code 保留；`reason` → `detail` 并标 diagnostic-only |
| `ERROR` | `code: String` + `message: String?` | **保持**——它不驱动任何分支，只进日志。这才是"开放字符串"的正当用法 |

五条理由：

1. **i18n**：项目已有"代码里禁止硬编码中文"的 lint 检查（`BASE_MODULE_GUIDE.md` §8）。把中文放到**线缆**上等于绕过这条检查，却产生同样的缺陷——要做多语言时得改协议。
2. **契约稳定性**：文案会改，枚举不会。文案上线缆 = 每次改措辞都要动协议。
3. **可测试性**：现在的测试把一个中文串钉成了契约。断言 `code == SEAT_OCCUPIED` 是断言语义；断言 `"座位 0 已被 张三 占据"` 会在下次改文案时假红。
4. **安全面**：服务端字符串直接进 UI，要处理长度、换行、表情、恶意内容——成本随即转移给 UI 角色。
5. **可观测性**：`code` 能做聚合指标，自由字符串不能。

建议枚举值：`LobbyRejectCode` = `SEAT_OUT_OF_RANGE` / `SEAT_OCCUPIED` / `NOT_IN_LOBBY`（已开局）/ `OP_UNSUPPORTED`，正好覆盖房主侧那四条校验；`KickReason` = `BY_HOST`；`ByeReason` = `HOST_DISSOLVED` / `HOST_EXIT`。

**版本处理**：`protoVersion = 2` 尚未对外发布，本裁决并入 v2，**不另升 3**。一旦 v2 被任何外部实现（iOS/Web 客户端、第三方）吃到，就必须升 3——这是"版本号只保护已部署端"原则的另一面。

### 12.4 变更流程

冻结 ≠ 不可改，但改的路径唯一：

1. 提 ADR（写清：为什么要改、影响哪些角色、需不需要协议版本号 +1）；
2. 架构师批准；
3. 跑角色表 §6 的四条边界检查；
4. 若涉及载荷 schema，追加游戏协议设计师评审。

**禁止的行为**：为了让自己的实现好写而顺手加字段/加参数。这是冻结之后最常见的破坏方式——它绕过评审、不写 ADR、不升版本，等到联调期才暴露为"两端模型不一致"。遇到这种情况请停下来说"我需要改冻结接口"，而不是"我先加一个默认参数"。
