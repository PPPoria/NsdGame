# `:net-proto` 协议规范 · v1（M0 定稿）

> **归属**：传输与消息协议（角色表 §2.4）。**签名审批**：架构师。
> **地位**：`docs/ARCHITECTURE.md` §4（消息协议）的**实现级细化**。冲突时架构文档优先，本文只负责"把已冻结的决策落到可执行的字节与代码上"。
> **版本**：协议 `protoVersion = 1`。改帧格式 / 改类型表 / 改信封字段 → 必须 +1（见 §6）。
> **状态**：M0 已交付并通过验收（帧编解码 + TCP 传输，32 个单测全绿，0.7s）。

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
| `proto` | u8 | `1` | 版本不匹配由**会话层**回 `REJECT(VERSION_MISMATCH)`，帧层不拦（帧层拦了就没法回 REJECT 了） |
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

| 码 | 类型 | 方向 | 载荷归属 | 载荷要点 |
|---:|---|---|---|---|
| 1 | `HELLO` | C→H | **传输角色** | protoVersion / gameId / gameVersion / playerId / nick / deviceModel |
| 2 | `WELCOME` | H→C | **传输角色** | roomInstanceId / sessionId / roomMeta |
| 3 | `REJECT` | H→C | **传输角色** | `RejectCode`(VERSION_MISMATCH / ROOM_FULL / IN_PLAY / PASSCODE_WRONG / BANNED) + reason |
| 4 | `JOIN_REQ` | C→H | **传输角色** | passcode / nick |
| 5 | `JOIN_OK` | H→C | **传输角色** | mySeat / sessionToken / players[] / phase |
| 6 | `SNAPSHOT` | H→C | 游戏协议设计师 | seq / stateHash / state / phase / players[] |
| 7 | `CMD` | C→H | 游戏协议设计师 | cmdSeq / payload |
| 8 | `CMD_RESULT` | H→C | 游戏协议设计师 | cmdSeq / accepted / rejectReason? |
| 9 | `EVENT_BATCH` | H→C | 游戏协议设计师 | fromSeq / events[seq, payload]（≤16 条/批） |
| 10 | `RESUME_REQ` | C→H | **传输角色** | token / fromSeq |
| 11 | `SYNC_HASH` | H→C | 游戏协议设计师 | seq / stateHash（每 ~5s 或每 10 事件） |
| 12 | `PING` | 双向 | **传输角色** | 无载荷 |
| 13 | `PONG` | 双向 | **传输角色** | 无载荷 |
| 14 | `KICK` | H→C | **传输角色** | reason |
| 15 | `BYE` | H→C | **传输角色** | reason |
| 16 | `ERROR` | 双向 | **传输角色** | 协议级错误，随后断开 |

**区间约定**（给未来的自己留退路）：`1..5` 握手、`6..11` 游戏载荷、`12..16` 心跳与控制。
**类型码是线格式，归传输角色**（`MsgType` 在角色表 §3 是 FROZEN + 传输所有）；`6..11` 的**载荷字段**归游戏协议设计师。

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
| 调度器 | 专属 `newFixedThreadPool(8)`，线程名 `nsdnet-N`（**不用 `:base` 的 ThreadPool**：AbortPolicy 会炸掉一次发送） |
| 读超时 | `Socket.soTimeout`，默认 0 = 阻塞到有数据（心跳判定归会话层） |
| 写超时 | 每次 `send` 用 `withTimeoutOrNull(10s)` 兜住；超时即断开并抛 `IOException`，**不静默丢包** |
| 帧长上限 | 读侧 `PAYLOAD_TOO_LARGE` → `Protocol` 断开；写侧编码期自检 |
| 背压 | 出站队列 128、入站缓冲 64；满了 `send` / 读循环挂起 → 对端被 TCP 窗口背压 |

### 5.1 ⚠️ 线程池容量边界（部署时必须知道）

**每条连接的读循环会长期占住一个线程**（阻塞 IO 的代价），所以单实例可承载连接数 ≈ `poolSize - 1`（留一个跑 accept）。

- 标准形态够用：房主进程只 `listen`（6 人 = 5 条连接 + accept = 6 个线程），客端进程只 `connect`（1 条）。
- **同一实例既 listen 又 connect 时（单测常见）必须调大 `poolSize`**，否则读协程抽干线程、写协程拿不到线程，
  表现为"写超时 10s"。本项目的 `TcpTransportTest` 因此把房主侧与客端侧拆成两个实例。
- `:app` 的 `LanRuntime` 若要在一台设备上同时"建房 + 作为客端加入别的房间"，必须显式传更大的 `poolSize`（建议 `2 × 预期连接数 + 4`）。

---

## 6. 版本策略（三层，各管一件事）

| 层 | 字段 | 策略 | 谁负责拦 |
|---|---|---|---|
| 帧/消息集合 | `protoVersion`（帧头 u8 + `HELLO`） | **只支持相同大版本**，不等即 `REJECT(VERSION_MISMATCH)` | 会话层（帧层不拦，否则连 REJECT 都发不出去） |
| 规则一致性 | `gameId + gameVersion` | major/minor 不同即拒绝，patch 允许；规则不同**绝不允许开局**（D5 下必然漂移） | 会话层 + `GamePlugin.version` |
| 快照反序列化 | `stateSchemaVersion` | `ignoreUnknownKeys` + 字段默认值做前向兼容；**删字段/改类型必须升版本**并给明确错误 | 游戏协议设计师 |

升 `protoVersion` 的触发条件（任一）：改帧头布局、改类型码表、改帧体 JSON 结构、改 `Envelope` 字段。

---

## 7. 与其它角色的接缝

| 接缝 | 本角色保证 | 对面保证 |
|---|---|---|
| `Envelope.payload` | 这段 JSON **完整送达**（半包/粘包/乱序都由帧层吸收） | 这段 JSON **有意义**（schema + 语义） |
| `FrameCodec.decodePayload` | 未注册的类型原样返回 `JsonElement`；注册表由 `JsonFrameCodec(payloadDecoders = …)` 注入 | 提供 `MsgType → KSerializer` 注册项 |
| `CloseReason` | 只**产出**原因，不做重连决策 | 会话层决定退避/保座/踢人 |
| `Listener.localPort` | `listen()` 返回即可用 | 服务发现拿到后再 `registerService` |

---

## 8. M0 交付与验收对照（ARCHITECTURE.md §8）

| 验收标准 | 覆盖它的测试 |
|---|---|
| ① 任意长度 payload（1B~200KB）round-trip 100% 一致 | `FrameCodecTest.任意长度载荷 round-trip 完全一致`（0/1/9/64/1K/64K/200K 字节）+ 恰好 256 KiB 边界用例 |
| ② 每个偏移量切分喂给 `readFrame`，结果一致 | `FrameCodecTest.在每一个偏移量处切分都能解析出同一结果`（`cut ∈ 0..size` × `maxChunk ∈ {1,5,9}`）+ `一次只读 1 字节也不能丢半包` |
| ③ 帧长超 256 KiB 抛 `Protocol` 且不断开其它连接 | `TcpTransportTest.声明超长载荷时以 Protocol 断开且不影响其它连接` |
| ④ 表头 1 字节错乱立即拒绝 | `TcpTransportTest.表头 1 字节错乱立即以 Protocol 断开` + `FrameCodecTest.magic 错乱立即拒绝` |
| ⑤ 1000 条连续发送，接收端顺序 100% 正确 | `TcpTransportTest.一条连接上 1000 条消息顺序完全正确` |

补充覆盖：粘包（连续 100 帧）、截断头/截断载荷、未知 type、非 0 flags、非法 JSON、缺 `payload`、
u32 越界、关闭后 `send` 立即失败、监听关闭后拒绝新连接、6 客端并发、`FakeTransport` 语义对齐。

**测试规模**：`:net-proto` 20 个用例 0.27s；`:net-core` 12 个用例 0.39s（目标分别是 < 2s / < 15s）。

---

## 9. 尚未交付（明确留给下一步）

| 内容 | 交给 | 前置条件 |
|---|---|---|
| `net-proto/session/` 的载荷数据类（`Hello` / `Welcome` / `Reject` / `JoinReq` / `JoinOk` / `Ping` / `Pong` / `Bye` / `Kick` / `ResumeReq` / `Error`） | 传输角色（M1，我自己接着做） | `WELCOME.roomMeta` 与 `JOIN_OK.players[] / phase` 需要引用架构师所有的 `room/` 类型（`RoomPhase` / `PlayerMeta` 等），需先由架构师落地 `:net-proto/room/` 再定字段 |
| `net-proto/game/` 载荷 schema（`CMD` / `EVENT_BATCH` / `SNAPSHOT` / `SYNC_HASH` / `CMD_RESULT`） | 游戏数据流/协议设计师 | 外壳 `Envelope` 已冻结；载荷字段随五子棋规则长（§12.1 有意不冻结） |
| 心跳 / 超时机制（5s PING、30s 判死） | 会话层（`:net-core/session`，游戏协议设计师） | 本层已提供 `soTimeout` 旋钮与单调时钟注入点（`clock: TimeSource` 在 `RoomSession` 构造签名上） |
| 重连 / 保座 / 踢人 | 会话层 | 本层只提供 `CloseReason`，不做决策（禁止项 ②） |
