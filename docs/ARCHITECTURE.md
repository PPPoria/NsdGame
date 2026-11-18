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
typealias Message = ByteArray   // 一条完整消息的 payload，不含长度头

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
  │──── HELLO{protoVer, gameId, gameVer,    │
  │           playerId, nick} ─────────────▶│ 校验版本
  │◀─── WELCOME{roomInstanceId, sessionId, roomMeta} ─│
  │──── JOIN_REQ{passcode, nick} ──────────▶│ 校验口令 / 座位 / 阶段
  │◀─── JOIN_OK{mySeat, sessionToken, players[], phase} ─│  → ACTIVE
  │◀─── SNAPSHOT{seq, state, hash}（若已开局）───────────│
```

口令由**房主明文比对**（D6）。客端不缓存旧口令重试。

**② 开局**

```
Host UI ─ startGame(config) ─▶ HostSession
   │ canStart(seats) == false → 直接回 UI 错误，不发网络包
   │ 生成 GAME_STARTED{config, seats[], initialSnapshot, seq=1}
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
2b. 房主 → SUSPENDED，广播 PLAYER_OFFLINE{seat}，不动座位、不产生游戏事件
3. 重连成功 → 校验 token → ACTIVE → 发 SNAPSHOT{seq, state, hash}
   （不重放事件窗口：重连时状态可能已大幅前进，全量快照更省事且不漏）
4. 60s 过期 → DROPPED → 广播 PLAYER_LEFT{seat} → 交给插件决定
```

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
| C→H | `HELLO` | protoVersion, gameId, gameVersion, playerId, nick, deviceModel |
| H→C | `WELCOME` | roomInstanceId, sessionId, roomMeta |
| H→C | `REJECT` | `RejectCode`(VERSION_MISMATCH / ROOM_FULL / IN_PLAY / PASSCODE_WRONG / BANNED) + reason |
| C→H | `JOIN_REQ` | passcode, nick |
| H→C | `JOIN_OK` | mySeat, sessionToken, players[], phase |
| H→C | `SNAPSHOT` | seq, stateHash, state, phase, players[] |
| C→H | `CMD` | cmdSeq, payload |
| H→C | `CMD_RESULT` | cmdSeq, accepted, rejectReason? |
| H→C | `EVENT_BATCH` | fromSeq, events[seq, payload]（≤16 条/批） |
| C→H | `RESUME_REQ` | token, fromSeq |
| H→C | `SYNC_HASH` | seq, stateHash（每 ~5s 或每 10 事件） |
| 双向 | `PING` / `PONG` | 无载荷 |
| H→C | `KICK` / `BYE` | reason |
| 双向 | `ERROR` | 协议级错误，随后断开 |

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

### 12.3 变更流程

冻结 ≠ 不可改，但改的路径唯一：

1. 提 ADR（写清：为什么要改、影响哪些角色、需不需要协议版本号 +1）；
2. 架构师批准；
3. 跑角色表 §6 的四条边界检查；
4. 若涉及载荷 schema，追加游戏协议设计师评审。

**禁止的行为**：为了让自己的实现好写而顺手加字段/加参数。这是冻结之后最常见的破坏方式——它绕过评审、不写 ADR、不升版本，等到联调期才暴露为"两端模型不一致"。遇到这种情况请停下来说"我需要改冻结接口"，而不是"我先加一个默认参数"。
