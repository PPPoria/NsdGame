# `:net-proto` 协议规范 · v1（M0 定稿）

> **归属**：传输与消息协议（角色表 §2.4）。**签名审批**：架构师。
> **地位**：`docs/ARCHITECTURE.md` §4（消息协议）的**实现级细化**。冲突时架构文档优先，本文只负责"把已冻结的决策落到可执行的字节与代码上"。
> **版本**：协议 `protoVersion = 2`（v1 → v2：ADR-019 新增 `LOBBY_REQ`/`LOBBY_RESULT`）。改帧格式 / 改类型表 / 改信封字段 → 必须 +1（见 §6）。
> **状态**：M0 已交付并通过评审；**M1 传输侧（11 类会话/大厅载荷 + 注册表）已交付**；`protoVersion = 2`。
> **61 个单测全绿**，1.8s。**协议线已冻结**（架构师确认不再加协议变更）。
> 已落实：ADR-013（线程池派生）、ADR-014（`Message` = 整帧）、ADR-015（线缆投影）、
> ADR-016（不新增 `GAME_STARTED`）、ADR-017（大厅变更走快照 + `seq` 三条推论）、
> ADR-018（`JOIN_OK` 只留私有字段）、ADR-019（`LOBBY_REQ`/`LOBBY_RESULT` + 版本 1→2）、
> ADR-020（驱动 UI 的字段必须是枚举，诊断字段标 `diagnostic-only`）。

---

## 1. 帧格式（9 字节定长头 + JSON 载荷）

```
 0      1      2       3      4       5      6      7      8      9    ...
+------+------+-------+------+-------+------+------+------+------+-------------------+
| 'N'  | 'G'  | proto | type | flags |      payloadLen : u32 BE       | payload(JSON UTF-8) |
| 0x4E | 0x47 |  u8   |  u8  |  u8   |  (4 字节大端，载荷字节数)        |                   |
+------+------+-------+------+-------+------+------+------+------+-------------------+
  └─ magic 2B ─┘      └──────────────── 9B 固定头 ─────────────────┘
```

| 字段 | 类型 | 值 | 约束 |
|---|---|---|---|
| `magic` | 2B | `'N' 'G'`（0x4E 0x47） | 不匹配 → `FrameProtocolException(BAD_MAGIC)` 立即断开。用来区分"连上了但对面不是本协议"（手输 IP 输错） |
| `proto` | u8 | `2` | 版本不匹配由**会话层**回 `REJECT(VERSION_MISMATCH)`，帧层不拦（帧层拦了就没法回 REJECT 了） |
| `type` | u8 | 见 §2 | 不在类型表内 → `BAD_TYPE` 立即断开 |
| `flags` | u8 | `0` | 当前只允许 0（预留给压缩/加密/分片）。非 0 → `UNSUPPORTED_FLAGS`，**故意严格**：宁可断开也不猜 |
| `payloadLen` | u32 BE | `0 .. 262144` | 上限 **256 KiB**。越界（含 u32 高位导致的负数）→ `PAYLOAD_TOO_LARGE`，**不读载荷直接断开**：防一个坏包让 6 个连接同时吃内存 |
| `payload` | n B | JSON | 见 §3 |

**大端固定**：Java/Kotlin 的 `DataOutput` 即为大端，`ByteBuffer` 默认大端，没有歧义。

### 1.1 `Message` 的确切语义（重要澄清）

`Message`（`:net-proto` 的 `com.poria.net.proto.frame.Message`，与 `:net-core` 的
`com.poria.net.core.transport.Message` 是**同一个** `ByteArray` 别名）=

> **一整帧字节**：9 字节帧头 + JSON 载荷，原样可写入 socket。

这个澄清解决了一处文档措辞歧义，且**不构成签名变更**（`Message` 仍是 `ByteArray`）：

- `FrameCodec.encode(env)` 的返回值 = `Message` = 整帧 → `Connection.send(msg)` 原样写出，**不需要再加工**；
- `Connection.incoming` 吐出的每一条 `Message` = 整帧 → 会话层用 `FrameCodec.readFrame(ByteArrayInputStream(msg))` 拿到 `Envelope`；
- 因此 **`:net-core/transport` 全程不解析 JSON**，真正做到"只搬不解析"（禁止项 ①）。

后果：`payloadLen` 是"载荷长度"，不含 9 字节头；一帧总长 = `9 + payloadLen`。

---

## 2. 消息类型表（`MsgType`，帧头 `type` 字节）

| 码 | 类型 | 方向 | 载荷归属 | 载荷概要 |
|---:|---|---|---|---|
| 1 | `HELLO` | C→H | **传输角色** | gameId / gameVersion / playerId / nick / deviceModel（**不含 protoVersion**） |
| 2 | `WELCOME` | H→C | **传输角色** | room（加入**前**快照，无地址候选）/ sessionId |
| 3 | `REJECT` | H→C | **传输角色** | code（`RejectCode` 五值）/ reason? |
| 4 | `JOIN_REQ` | C→H | **传输角色** | passcode? / nick |
| 5 | `JOIN_OK` | H→C | **传输角色** | sessionToken / mySeat（**均为私有字段，权威状态只来自 `SNAPSHOT`**） |
| 6 | `SNAPSHOT` | H→C | 游戏协议设计师 | **唯一权威状态来源**：seq / stateHash / state / room / players[]（ADR-017/018） |
| 7 | `CMD` | C→H | 游戏协议设计师 | cmdSeq / payload |
| 8 | `CMD_RESULT` | H→C | 游戏协议设计师 | cmdSeq / accepted / rejectReason? |
| 9 | `EVENT_BATCH` | H→C | 游戏协议设计师 | fromSeq / events[seq, payload]（≤16 条/批） |
| 10 | `RESUME_REQ` | C→H | **传输角色** | token / fromSeq |
| 11 | `SYNC_HASH` | H→C | 游戏协议设计师 | seq / stateHash（每 ~5s 或每 10 事件） |
| 12 | `PING` | 双向 | **传输角色** | 无载荷（`payload: null`） |
| 13 | `PONG` | 双向 | **传输角色** | 无载荷（`payload: null`） |
| 14 | `KICK` | H→C | **传输角色** | reason? |
| 15 | `BYE` | H→C | **传输角色** | reason? |
| 16 | `ERROR` | 双向 | **传输角色** | code / message?（致命） |
| 17 | `LOBBY_REQ` | C→H | **传输角色** | reqSeq / op（`ChangeSeat` \| `Leave`）/ seat? |
| 18 | `LOBBY_RESULT` | H→C | **传输角色** | reqSeq / op / ok / reason?（**软拒绝，不断连**） |

**区间约定**（给未来的自己留退路）：`1..5` 握手、`6..9`+`11` 游戏载荷、`10` 是**会话层**的重连请求
（`RESUME_REQ` 不含游戏语义，所以归传输角色所有，不随 6..11 一起交给游戏协议设计师）、
`12..16` 心跳与控制、`17..18` 大厅意图（ADR-019）。
**类型码是线格式，归传输角色**（`MsgType` 在角色表 §3 是 FROZEN + 传输所有）；`6..9`/`11` 的**载荷字段**归游戏协议设计师。

### 2.1 会话载荷字段（权威，FROZEN）

本节是**会话消息的完整字段表**。判定标准：一个不读 Kotlin 的实现者只靠本节就能互通。
Kotlin 侧对应 `:net-proto/session/` 的 `Hello` / `Welcome` / `Reject`(+`RejectCode`) / `JoinReq` /
`JoinOk` / `ResumeReq` / `Kick` / `Bye` / `ProtocolError`，注册表为 `SessionPayloads.decoders`。

**1 `HELLO`（C→H）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `gameId` | string | 插件 id，如 `"gomoku"` |
| `gameVersion` | string | 语义版本，如 `"1.2.0"`；major/minor 不同即拒绝 |
| `playerId` | string | 跨房间稳定的玩家身份 |
| `nick` | string | 昵称（仅展示） |
| `deviceModel` | string | 排障信息，缺失不影响判定（JVM 假客户端填 `"jvm"`） |

> **不含 `protoVersion`**：帧头 `proto` 字节已经是它。与帧体不重复 `type` 同一原则——线格式里每个事实只出现一次。

**2 `WELCOME`（H→C）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `room` | object（`RoomDescriptor`，见 §2.4） | 加入**前**的房间快照；`addresses` 必须为空（对方已连上来）。**`not-authoritative`**：只用于加入前展示与版本预检 |
| `sessionId` | string | 本次连接的会话 id（日志关联用，**不是**重连凭证） |

**3 `REJECT`（H→C，随后断开）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | enum | `VERSION_MISMATCH` / `ROOM_FULL` / `IN_PLAY` / `PASSCODE_WRONG` / `BANNED`（线缆为枚举名原文）。**驱动 UI**：客端按它查本地文案 |
| `detail` | string? | `diagnostic-only`：只进日志/上报，**禁止展示给用户** |

**4 `JOIN_REQ`（C→H）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `passcode` | string? | 房间口令（**明文**，D6）；无口令房间为 `null` |
| `nick` | string | 以本字段为准（可改写 `HELLO.nick`） |

**5 `JOIN_OK`（H→C，随后连接进入 ACTIVE）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionToken` | string | 重连凭证的字符串形态。**私有**：绝不能进广播 `SNAPSHOT`（否则等于把每个人的重连凭证发给所有人，D3 保座机制立刻作废） |
| `mySeat` | int | 分配给我的座位号（裸 Int）。**`not-authoritative`**：权威座位号是 `SNAPSHOT.players[]` 里 `playerId == 我` 那一条的 `seat`；本字段只为"刚加入时立刻知道坐哪"（此时快照可能还在路上） |

> **ADR-018：`players[]` 与 `room` 已从 `JOIN_OK` 移出。**
> `JOIN_OK` 是**一刻**，名册是**持续**——把持续状态塞进一刻消息，UI 必然按一次性渲染，
> 之后换座、掉线、被踢全部漏掉。房主在同一次状态读取之后紧接着发 `SNAPSHOT`，
> 两帧因此不可能互相矛盾（= "第三次握手同时携带数据"）。
> **权威状态只有一个来源 = `SNAPSHOT`。**

**6 `RESUME_REQ`（C→H）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | string | `JOIN_OK.sessionToken` 原样回传 |
| `fromSeq` | long | 客端最后成功应用的 `seq`（0 = 还没应用任何事件）；当前房主策略是回全量快照，此字段用于日志/诊断 |

**7 `PING` / `PONG`（双向）**：`payload` 为 `null`，没有字段。**不是"还没实现"**——它们不携带信息，
`decodePayload` 返回 `JsonNull` 就是完整答案。

**8 `KICK` / `BYE`（H→C）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | enum | `KICK`: `BY_HOST`；`BYE`: `HOST_DISSOLVED` / `HOST_EXIT`（**驱动 UI**，ADR-020） |
| `detail` | string? | `diagnostic-only`：只进日志，**禁止展示给用户** |

> `KICK` = 你被踢出但房间还在；`BYE` = 房主关房。客端文案必须按 `code` 区分二者，
> 且"没收到 `BYE` 就断线"只能提示"与房主失去连接"（ARCHITECTURE.md §3.4 ⑤）。
> 三个枚举值互不替代：`HOST_DISSOLVED`（房间已解散）与 `HOST_EXIT`（房主退出）在客端是两种文案。

**9 `ERROR`（双向，随后断开）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | string | **开放的**协议错误码，如 `"UNEXPECTED_MESSAGE"`。`diagnostic-only` |
| `message` | string? | 可读描述（日志用）。`diagnostic-only` |

> **ADR-020 的例外**：ADR-020 的规则是"**驱动 UI 表现的字段**必须是枚举"，不是"线缆不许有字符串"。
> 本消息不驱动任何 UI 分支（客端只会断开并进日志），所以开放字符串是正当用法。
> 若把"线缆不许有 String"当成规则，就会把这条一起误杀。

**10 `LOBBY_REQ`（C→H，ADR-019）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `reqSeq` | int | 请求序号（客端自增，**只做请求/回执关联**，不做幂等去重——`ChangeSeat`/`Leave` 天然幂等） |
| `op` | enum | `ChangeSeat` / `Leave`（线缆为字面量原样，非 Kotlin 常量名） |
| `seat` | int? | 目标座位号（裸 Int）。仅 `ChangeSeat` 时有意义；`Leave` 时不写出 |

> 为什么不用 `CMD`：`CMD` 是**游戏**命令（`PLAYING` 期才有意义，schema 归游戏协议设计师、校验归插件）；
> 换座发生在 `LOBBY`，让插件校验换座是错的所有者。
> 为什么必须有：五子棋 0 号位 = 执黑 = 先手，两人都想执黑时"退出重进"会重新分配座位——等于没解。

**11 `LOBBY_RESULT`（H→C，ADR-019 / ADR-020）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `reqSeq` | int | 回显对应 `LOBBY_REQ.reqSeq` |
| `op` | enum | 回显操作类型 |
| `ok` | bool | 是否接受。**`false` 时连接保持**（软拒绝） |
| `code` | enum? | 拒绝码：`SEAT_OUT_OF_RANGE` / `SEAT_OCCUPIED` / `NOT_IN_LOBBY` / `OP_UNSUPPORTED`。**`ok=false` 时必填，`ok=true` 时为 null** |
| `detail` | string? | `diagnostic-only`：只进日志，**禁止展示给用户** |

房主侧校验与拒绝码的对应（会话层实现依据）：

| 校验失败 | 回的 `code` |
|---|---|
| `seat` 越界（或 `ChangeSeat` 没带 `seat`） | `SEAT_OUT_OF_RANGE` |
| 目标座位已被占 | `SEAT_OCCUPIED` |
| `phase != Lobby`（D2：开局后座位冻结） | `NOT_IN_LOBBY` |
| 房主不认识的 `op` | `OP_UNSUPPORTED` |

> **软拒绝 vs 致命拒绝不得混用**（ADR-018 硬约束）：
> `LOBBY_RESULT(ok=false)` = 连接保持；`REJECT` / `ERROR` = 断开。
> UI 侧义务（留给交互角色）：收到 `ok=false` **按 `code` 查本地字符串资源**展示（多语言因此自动），
> 不要拼自由文本；且收到新 `SNAPSHOT` 后必须以快照为准重绘（名册权威来源永远是 `SNAPSHOT.players`）。

### 2.2 载荷类型的硬规则（ADR-015 / 017 / 018）

| 规则 | 说明 |
|---|---|
| **权威状态只有一个来源 = `SNAPSHOT`** | 其它消息里的 `WELCOME.room`、`JOIN_OK.mySeat` 一律标注 `not-authoritative`，只能用于"加入前展示 / 加入瞬间定位"，永远以最近一条 `SNAPSHOT` 为准（ADR-018） |
| **一刻消息不携带持续状态** | `JOIN_OK` 只留私有字段（`sessionToken`、`mySeat`）。名册/阶段/人数都在 `SNAPSHOT`：否则 UI 按一次性渲染，换座与掉线必漏 |
| **软拒绝与致命拒绝不得混用** | `LOBBY_RESULT(ok=false)` / `CMD_RESULT(rejected)` = 连接保持；`REJECT` / `ERROR` = 随后断开 |
| **大厅状态变更一律走 `SNAPSHOT`** | 换座、上下线、退出、改设置、他人感知被踢 → 广播 `SNAPSHOT`，客端整体替换。**不新增类型**（ADR-017，与 ADR-016 同构） |
| **驱动 UI 的字段必须是枚举**（ADR-020） | `REJECT.code` / `KICK.code` / `BYE.code` / `LOBBY_RESULT.code` / `CMD_RESULT` 的拒绝码。自由字符串只允许出现在**纯诊断字段**（`detail` / `ERROR.code` / `ERROR.message`）并标注 `diagnostic-only` |
| **诊断字段禁止直接展示** | `detail` 只进日志/上报。UI 文案一律由 `code` 查本地 `strings.xml`——这样 i18n 是自动的，改措辞不动协议 |
| `JsonElement` **只允许**出现在游戏载荷 | `CMD` / `CMD_RESULT` / `EVENT_BATCH` / `SNAPSHOT.state` —— 那些 schema 要跟着规则演进，是**有意的接缝** |
| 握手与会话消息**每个字段必须有具体类型** | `HELLO`..`JOIN_OK`、`RESUME_REQ`、`PING`/`PONG`、`KICK`/`BYE`/`ERROR`、`LOBBY_REQ`/`LOBBY_RESULT` 一律不允许 opaque 字段 |
| 线缆用**裸 `Int`** 座位号 | `:game-api` 的 `Seat` 不上线缆；上线的名册统一用 `RoomPlayer` |
| 名册是 `RoomPlayer`，不是 `PlayerMeta` | 线缆投影 vs 领域模型：插件永远看不到 `RoomPlayer`，proto 永远看不到 `Seat`；映射只在会话层做一次 |
| `RoomPhase.wireValue` 是**单一真相源** | NSD TXT 与 JSON 阶段值同源。各写一份字面量会导致「列表显示 Lobby、加入时被拒为 InPlay」这类极难复现的不一致 |
| 不新增 `GAME_STARTED`（ADR-016） | 开局 = 广播 `SNAPSHOT{state, room.phase=Playing, players[]}`；终局 = `SNAPSHOT{room.phase=Finished}`。`SNAPSHOT` 自带 `seq` 与 `phase`，"先收到 SNAPSHOT 后收到 GAME_STARTED 怎么办"这类问题因此不存在 |

### 2.3 `SNAPSHOT` 在**大厅期**的语义（ADR-017 钉死的四条）

`SNAPSHOT` 的载荷 schema 归游戏协议设计师，但以下四条**协议层语义**已经定死（骨架实现只需遵守，不必再问）：

| # | 语义 | 理由 |
|---|---|---|
| 1 | 大厅快照与对局快照**共用同一个 `seq` 计数器** | 否则"开局瞬间"会出现两个序号空间，客端的 `lastAppliedSeq` 在边界上必然错乱 |
| 2 | 大厅期 `state` 允许为 `{}`（没有规则状态） | 大厅还没有棋盘；不要为"大厅"造第二个快照类型 |
| 3 | `players[]` 是**权威名册**（含 `online`、`host`），客端不得本地增删 | 与 D3 保座一致：`online=false` 时座位仍然占用 |
| 4 | **大厅期变更走快照；对局期不做名册变更** | D2 已冻结座位（`PLAYING` 期不允许增删），所以第 4 条自动成立，不需要额外规则 |

> 顺带修正一处措辞：ADR-016 里写的 `SNAPSHOT{seq=1, ...}` 是**初始值**；
> 由于第 1 条（单计数器），若大厅期已发生过变更，开局快照的 `seq` 就是当时的计数值，未必等于 1。
> 客端不能假设"开局快照的 `seq` 一定是 1"。

#### 2.3.1 关于 `seq` 的三条硬推论（ADR-017 补充）

| # | 推论 | 违反后的症状 |
|---|---|---|
| 1 | **禁止用 `seq` 判定任何语义**（尤其"是不是开局"）——只看 `room.phase` | 房主开房 → 第二人加入（`seq=1`）→ 开局（`seq=2`），写死 `seq==1` 的代码永远不会触发 |
| 2 | `lastAppliedSeq` **只能由 `SNAPSHOT.seq` 初始化**，不得假设从 0/1 开始，也不得假设连续 | 重连拿到的快照可能是很大的值，假设从 0 或 1 起步会算出巨大的"断层"并疯狂请求补发 |
| 3 | `seq` 是**会话级单调量**，不是回合数、不是事务序号 | 任何拿它当"第 n 步"用的代码，都会在换座/掉线之后整体错位 |

### 2.4 `:net-proto/room/` 的线缆类型（架构师所有，FROZEN）

会话载荷复用它，**不要**再造近似的元模型。

```jsonc
// RoomDescriptor —— NSD TXT / WELCOME / SNAPSHOT 共用同一个类型
{"protoVersion":2,"gameId":"gomoku","gameVersion":"1.2.0","roomInstanceId":"<uuid>",
 "roomName":"张三的房间","hostNick":"张三","playerCount":3,"maxPlayers":6,
 "phase":"Lobby","hasPasscode":true,"addresses":[{"ip":"192.168.1.7","port":41234}]}

// RoomPlayer —— 名册条目（线缆投影，零游戏语义）；出现在 SNAPSHOT.players
{"seat":1,"playerId":"p-1","nick":"李四","host":false,"online":true}
```

`online=false` 就是"保座 60s"在线缆上的表达：**座位照样占用**。
`phase` 取值只有 `"Lobby"` / `"Playing"` / `"Finished"`（`RoomPhase.wireValue`，解析时大小写不敏感，
未知值返回 `null` 由会话层决定策略——当前策略是拒绝加入并回 `REJECT`，不猜）。

---

## 3. 信封（`Envelope`）与帧体

`Envelope` 是**公开 API**（FROZEN），但线格式上做了去冗余：

```jsonc
// 帧体（payload 字段的内容，UTF-8）
{"seq":41,"cmdSeq":0,"payload":{ /* 不透明，按 type 解释 */ }}
```

- `type` 与 `proto` 只在**帧头**里出现一次，JSON 里**不重复**（省字节 + 消灭两处不一致的可能）。
- `seq` / `cmdSeq` 在帧体里，因为它们是每个消息都可能有的字段；默认 0 仍然写出（`encodeDefaults = true`，抓包可读）。
- `payload` 是 `JsonElement`，**永不解释内容**；`Envelope.payload` 是不是对象/数组/标量由游戏协议设计师决定。

`Json` 配置（`ProtocolJson`）：`ignoreUnknownKeys = true`（前向兼容）、`encodeDefaults = true`、`explicitNulls = false`。

**发送前的体积自检**：`JsonFrameCodec.encode` 若发现帧体超过 256 KiB，**在编码期就抛** `PAYLOAD_TOO_LARGE`，
而不是发出去让对端断开——这样错误出现在调用栈里，而不是出现在对面设备的日志里。

---

## 4. 帧层错误语义（`FrameProtocolException`）

| `Kind` | 触发 | 传输层动作 |
|---|---|---|
| `BAD_MAGIC` | 前两字节不是 `'N''G'` | `CloseReason.Protocol` |
| `BAD_TYPE` | `type` 不在类型表内 | `CloseReason.Protocol` |
| `UNSUPPORTED_FLAGS` | `flags != 0` | `CloseReason.Protocol` |
| `PAYLOAD_TOO_LARGE` | 长度 > 256 KiB 或 u32 越界 | `CloseReason.Protocol`（**不读载荷**） |
| `TRUNCATED_HEADER` | 帧头读到一半 EOF | `CloseReason.Protocol` |
| `TRUNCATED_PAYLOAD` | 载荷读到一半 EOF | `CloseReason.Protocol` |
| `MALFORMED_JSON` | 帧体不是合法 JSON / 缺 `payload` | `CloseReason.Protocol` |

**帧边界上的干净 EOF 不是协议错误**：`readFrame` 抛 `java.io.EOFException`，传输层映射成 `CloseReason.Eof`。
（于是 `Eof` = 对端正常关 socket；`RemoteClosed` = RST/连接被重置；两者对会话层的处理完全不同。）

---

## 5. 传输层实现要点（`TcpTransport`，ADR-008 落地）

| 项 | 实现 |
|---|---|
| 端口 | `listen(0)` → 系统分配；`Listener.localPort` 返回真实端口，**NSD 必须等它返回后再 register** |
| 并发模型 | 每连接 2 个协程：读循环 + 写 Channel |
| 调度器 | 专属 **cached** 线程池（ADR-013），线程名 `nsdnet-N`；**不用 `:base` 的 ThreadPool**（AbortPolicy 会炸掉一次发送），不蹭 `Dispatchers.IO` |
| 读超时 | `Socket.soTimeout`，默认 0 = 阻塞到有数据（心跳判定归会话层） |
| 写超时 | 每次 `send` 用 `withTimeoutOrNull(10s)` 兜住；超时即断开并抛 `IOException`，**不静默丢包** |
| 帧长上限 | 读侧 `PAYLOAD_TOO_LARGE` → `Protocol` 断开；写侧编码期自检 |
| 背压 | 出站队列 128、入站缓冲 64；满了 `send` / 读循环挂起 → 对端被 TCP 窗口背压 |

### 5.1 `FakeTransport` 必须与真实传输同语义（假绿的来源）

`FakeTransport` 的 `send` 是**挂起**的（`Channel.send`），不是 `trySend` 满了就抛。入站缓冲同为 64 条。
原因：会话层若在测试里依赖"send 会等"，真机背压时的行为与 Fake 不一致 —— 测试全绿、真机卡死。
帧上限校验也保留在 Fake 里，免得测试造出 TCP 永远发不出去的消息。

### 5.2 线程池：并发上界在会话层，不在传输层（ADR-013，修订 ADR-008）

原 ADR-008 把 `poolSize` 钉成常量 8。**已修改**：每条连接的读循环会长期占住一个线程（阻塞 IO 的必然代价），
所以"能撑多少连接"完全由连接数决定——写协程与读协程共用同一 dispatcher 时，房主 6 人 **峰值 7~11 线程即被抽干**，
症状是 `写超时 10000ms`，属于最难诊断的一类。

| 项 | 定稿 |
|---|---|
| 默认 | `maxIoThreads = null` → **cached 池**（空闲 60s 回收、有活才建线程）；线程数由连接数伸缩 |
| 真正的上界 | **会话层的 `maxPlayers = 6`**。把上界放在线程池是对错误的层设限 |
| 单实例同时 listen + connect | **安全**（M1 起不再需要拆成两个实例；`单实例同时 listen 与 connect：6 客端并发不抽干线程` 是这条回归的守门测试） |
| `maxIoThreads` 非 null | 切成 fixed 池，只作兜底旋钮（恶意对端狂开连接时给线程总数一个天花板）。注意：fixed 池满了会让读协程排队 → 又回到同一类"写超时"症状，正常部署不要设 |

> **回归测试有效性已实证**：把默认池临时改回 `maxIoThreads = 8`，该用例即以 `写超时` 失败；
> 改回 cached 后通过。所以这条测试不是"假绿"。

---

## 6. 版本策略（三层，各管一件事）

| 层 | 字段 | 策略 | 谁负责拦 |
|---|---|---|---|
| 帧/消息集合 | `protoVersion`（帧头 u8 + `HELLO`） | **只支持相同大版本**，不等即 `REJECT(VERSION_MISMATCH)` | 会话层（帧层不拦，否则连 REJECT 都发不出去） |
| 规则一致性 | `gameId + gameVersion` | major/minor 不同即拒绝，patch 允许；规则不同**绝不允许开局**（D5 下必然漂移） | 会话层 + `GamePlugin.version` |
| 快照反序列化 | `stateSchemaVersion` | `ignoreUnknownKeys` + 字段默认值做前向兼容；**删字段/改类型必须升版本**并给明确错误 | 游戏协议设计师 |

升 `protoVersion` 的触发条件（任一）：改帧头布局、改类型码表、改帧体 JSON 结构、改 `Envelope` 字段。

**变更史**

| 版本 | 变更 | 依据 |
|---|---|---|
| `1` | M0/M1 基线：9B 帧头 + `Envelope` + 16 个类型（1..16） | ADR-006 / ADR-007 |
| `2` | 新增 `LOBBY_REQ(17)` / `LOBBY_RESULT(18)`；**并入** ADR-020 的 5 处字段改造（`REJECT.reason`→`detail`、`KICK`/`BYE` 加 `code` 枚举、`LOBBY_RESULT` 用 `code`+`detail`） | ADR-019 / ADR-020 |

> **为什么 ADR-020 的字段改造并入 v2 而不升 3**：v2 尚未对外发布（没有外部实现吃过它），
> 所以并入零成本。**但只要 v2 被任何外部实现（iOS/Web、第三方）消费过，就必须升 3**——
> "版本号只保护已部署端"的另一面。

> **老端行为**：v1 客户端收到 type 17/18 会在帧层被判 `BAD_TYPE` 并断开——
> 这是**预期**的：只支持相同大版本，所以握手阶段就会先被 `REJECT(VERSION_MISMATCH)` 拦下，
> 根本走不到"收到未知类型"这一步。
> 反过来，**偷偷加类型却不升版本**会让"改类型表必须升版本"从规则退化成建议，等到真有
> Web/iOS 客户端时才集中爆炸，而那时的修复成本远高于现在（零外部实现）的一次版本跳变。

---

## 7. 与其它角色的接缝

| 接缝 | 本角色保证 | 对面保证 |
|---|---|---|
| `Envelope.payload` | 这段 JSON **完整送达**（半包/粘包/乱序都由帧层吸收） | 这段 JSON **有意义**（schema + 语义） |
| `Message`（ADR-014） | = **一整帧**（9B 头 + JSON 载荷），`send` 原样写出，不需要二次加工 | 会话层发给传输层的就是 `FrameCodec.encode` 的产物 |
| `FrameCodec.decodePayload` | 未注册的类型原样返回 `JsonElement`；注册表由 `JsonFrameCodec(payloadDecoders = …)` 注入 | 提供 `MsgType → KSerializer` 注册项 |
| `CloseReason` | 只**产出**原因，不做重连决策 | 会话层决定退避/保座/踢人 |
| `Listener.localPort` | `listen()` 返回即可用 | 服务发现拿到后再 `registerService` |

### 7.1 解码唯一入口（角色表 §6 R5）

| 层 | 允许的帧层入口 |
|---|---|
| `:net-core/transport` | `FrameIo.readRawFrame`（只切帧、**不解析 JSON**） |
| 会话层 `:net-core/session` | **只能** `FrameCodec.readFrame` / `decodePayload`；**禁止** `FrameIo` / `FrameHeader`（R5 grep） |
| 组合根 `:app/.../LanRuntime.kt`、`runtime/**` | 只允许 `Transport` / `TcpTransport` / `CloseReason`（R3 路径级豁免） |

机械保证（不只靠自觉）：`FrameIo` 里除 `readRawFrame` 外的三个辅助函数（`headerOf` / `bodyOf` / `frameOf`）
都是 `internal`，跨模块根本拿不到 —— 想写第二条解码路径会先在编译期失败。

---

## 8. M0 交付与验收对照（ARCHITECTURE.md §8）

| 验收标准 | 覆盖它的测试 |
|---|---|
| ① 任意长度 payload（1B~200KB）round-trip 100% 一致 | `FrameCodecTest.任意长度载荷 round-trip 完全一致`（0/1/9/64/1K/64K/200K 字节）+ `载荷恰好等于上限时可以编解码`（256 KiB 边界字节级相等） |
| ② 每个偏移量切分喂给 `readFrame`，结果一致 | `FrameCodecTest.在每一个偏移量处切分都能解析出同一结果`（`cut ∈ 0..size` × `maxChunk ∈ {1,5,9}`）+ `一次只读 1 字节也不能丢半包` |
| ③ 帧长超 256 KiB 抛 `Protocol`（**且不牵连其它连接**） | `TcpTransportTest.声明超长载荷时以 Protocol 断开且不影响其它连接`（双连接并发隔离）+ `一个连接违规只断自己：其它连接与监听器都不受影响`（3 好 1 坏：坏的口被断，3 条好连接双向仍通，监听器仍能接受新连接） |
| ④ 表头 1 字节错乱立即拒绝 | `TcpTransportTest.表头 1 字节错乱立即以 Protocol 断开` + `FrameCodecTest.magic 错乱立即拒绝` |
| ⑤ 1000 条**连续发送**，顺序 100% 正确且无粘包 | `TcpTransportTest.1000 条连续发送顺序完全正确且无粘包`（逐条校验 `seq` 与 payload，一条 `Message` 必须恰好解出一帧） |

> **验收标准 ⑤ 的措辞已修正**：原文"1000 条乱序发送"不适用——TCP 本身保证有序，能出问题的只有
> "发送端连续写、接收端是否按帧切回"。"乱序"这个词会诱导实现者去做没有意义的乱序测试。

补充覆盖：粘包（连续 100 帧）、截断头/截断载荷、未知 type、非 0 flags、非法 JSON、缺 `payload`、
u32 越界、关闭后 `send` 立即失败、监听关闭后拒绝新连接、**对端不消费时按写超时断开**
（`TcpTransport(writeTimeoutMs = 500)` + 只连不读、`SO_RCVBUF=1KB` 的对端）、
**`FakeTransport` 背压语义与真实传输对齐**（缓冲满时 `send` 挂起而非抛异常）、6 客端并发、
**ADR-013 回归**（`单实例同时 listen 与 connect：6 客端并发不抽干线程`：固定 8 线程时必挂，已实证）、
`maxIoThreads` 兜底旋钮仍可用。

**测试规模**：`:net-proto` 43 个用例 0.30s（其中会话载荷 23 个）；`:net-core` 18 个用例 1.45s
（目标分别是 < 2s / < 15s）。合计 **61 个用例**。`FakeTransportTest` 的 0.70s 是背压用例的等待窗口，
`TcpTransportTest` 的 0.76s 里含写超时用例真的等满 500ms。

---

## 9. M1 传输侧交付（`net-proto/session/`，含 ADR-019 追加）

| 交付物 | 内容 |
|---|---|
| 载荷数据类 | `Hello` / `Welcome` / `Reject`(+`RejectCode`) / `JoinReq` / `JoinOk`（ADR-018 瘦身）/ `ResumeReq` / `Kick`(+`KickReason`) / `Bye`(+`ByeReason`) / `ProtocolError` / `LobbyReq`(+`LobbyOp`) / `LobbyResult`(+`LobbyRejectCode`)（`PING`/`PONG` 无载荷） |
| 注册表 | `SessionPayloads.decoders`（11 项）+ `SessionPayloads.codec(json)` 工厂（`JsonFrameCodec` 已挂会话载荷） |
| 线缆契约测试 | `SessionPayloadsTest`（23 例）：11 类载荷的 round-trip + 线格式断言 + **把 ADR 变成可执行断言**：<br>· 键集断言——`WELCOME` 只能是 `{room, sessionId}`、`JOIN_OK` 只能是 `{sessionToken, mySeat}`（多一个字段都不行）<br>· `JOIN_OK` 不得携带 `players`/`room`<br>· ADR-020——`REJECT`/`KICK`/`BYE`/`LOBBY_RESULT` 必须出现 `code` 枚举且**不得出现 `reason`**；`ERROR` 的开放字符串必须保留<br>· `HELLO` 不含 `protoVersion`、`phase` 用 `wireValue`、`RoomPlayer` 扁平结构、`RejectCode`/`LobbyRejectCode` 逐字、`LOBBY_*` 类型码 17/18 与 `"ChangeSeat"`/`"Leave"` 字面量、`Leave` 不带 `seat`、帧头 `proto=2`、`seq`/`cmdSeq` 写出、未注册游戏载荷原样穿过 |
| 版本 | `ProtocolVersion.CURRENT = 2`（v1→v2：+`LOBBY_REQ`/`LOBBY_RESULT` + ADR-020 字段改造） |

会话层接入方式（一行）：

```kotlin
val codec = SessionPayloads.codec()                       // 只搬/解会话载荷
// 若同时要解游戏载荷（:net-proto/game/ 落地后）：
// JsonFrameCodec(payloadDecoders = SessionPayloads.decoders + GamePayloads.decoders)
```

## 10. 尚未交付（明确留给下一步）

| 内容 | 交给 | 前置条件 |
|---|---|---|
| `net-proto/game/` 载荷 schema（`CMD` / `CMD_RESULT` / `EVENT_BATCH` / `SNAPSHOT` / `SYNC_HASH`） | 游戏数据流/协议设计师 | 外壳 `Envelope` 已冻结；载荷字段随五子棋规则长（ARCHITECTURE.md §12.1 有意不冻结）。`JsonElement` 在这些载荷里是**有意的接缝**（ADR-015）。`SNAPSHOT` 是大厅/对局两份语义的**唯一载体**，需遵守 §2.3 的四条语义 |
| `RoomPlayer` ↔ `PlayerMeta`、`Int` ↔ `Seat` 的**映射**，以及 `WELCOME.room` / `SNAPSHOT` 的组装 | 会话层（游戏协议设计师） | 本层只搬线缆类型，不碰领域类型（ADR-015：映射只在会话层做一次） |
| 心跳 / 超时机制（5s PING、30s 判死） | 会话层（`:net-core/session`） | 本层已提供 `soTimeout` 旋钮与单调时钟注入点（`clock: TimeSource` 在 `RoomSession` 构造签名上） |
| 重连 / 保座 / 踢人 / 大厅意图的**服务端校验**（座位占用、`PLAYING` 期禁止换座） | 会话层 | 线缆与载荷已就绪；`LOBBY_RESULT.reason` 的文案由 UI 角色定 |
| 开局/终局的广播 | 会话层（ADR-016：复用 `SNAPSHOT`，不新增类型） | 本层不需要为开局加任何类型码 |
