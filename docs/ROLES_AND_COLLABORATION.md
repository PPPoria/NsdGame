# NsdGames · 角色分工与协作契约

> **地位**：本文档是 `:game-api` / `:net-*` / `:app` 各角色的边界契约，与 `BASE_MODULE_GUIDE.md`（`:base` + `:core` 契约）并列且互不覆盖。base 的契约优先于本文档。
> **配套文档**：`docs/ARCHITECTURE.md`（分层架构、接口签名、ADR 正文）。本文档的 `架构设计 §x.y` 锚点指向它。
> **维护者**：架构设计师（兼集成协调）。**变更方式**：只能走 ADR（见 §8），不接受直接编辑。
> **读者**：所有上层 AI 角色，以及通过评审进入的新角色。

---

## 0. 一句话总纲

> **每个角色只改"自己拥有"的 Kotlin 类型；接口签名只有架构设计师能批准；越界判定看"能否被编译器或 grep 抓到"，不看作者自觉。**

三条推论，全文围绕它们展开：

1. **所有权与审批权分离**：你可以"拥有"一个接口（继续演进它的实现），但"签名变更"必须架构师批准。
2. **边界必须是机械的**：凡是能写成 gradle 依赖方向、`internal` 可见性、grep 规则的禁止事项，就不写成文字条款。
3. **越界的定义是"改动了别人拥有的类型"**，不是"改得不好"。改得不好走评审，改错归属走驳回。

---

## 1. 决策权矩阵

`A` = 最终决定者，`R` = 必须评审，`I` = 只需知情。同一决策只有一个 `A`，这是本表存在的唯一理由。

| 决策项 | A | R | I |
|---|---|---|---|
| 模块划分、依赖方向、分层 | 架构师 | base 负责人 | 全部 |
| 模块的创建与 `build.gradle.kts` 内容 | **架构师** | 该模块的使用角色 | 全部 |
| `:base` / `:core` 任何改动 | **base 负责人**（一票否决） | 架构师 | 全部 |
| 任意公开接口的**签名变更** | 架构师 | 该接口的全部使用者 | 全部 |
| 帧格式、传输语义、心跳与超时参数 | 传输与消息协议 | 架构师 | 游戏协议设计师 |
| NSD 注册/发现/解析策略、多网卡地址选择 | 服务发现与连接管理 | 架构师 | UI 流程设计师 |
| 消息**载荷**语义（Event / Command / Snapshot 的 schema 与语义） | 游戏数据流/协议设计师 | 传输与消息协议 + 架构师 | 数据流开发 |
| 权威状态机、开局/终局条件、掉线后的规则动作 | 游戏数据流/协议设计师 | 架构师 | 数据流开发 |
| 单个游戏的规则细节与实现 | 数据流开发 | 游戏数据流/协议设计师 | — |
| UI 流程、状态绑定约定、异常文案策略 | 交互/UI 流程设计师 | 架构师 | 交互开发 |
| 界面实现、渲染、动画 | 交互开发 | 交互/UI 流程设计师 | — |
| 里程碑是否通过、能否联调 | 架构师（集成协调） | 全部相关角色 | 全部 |

---

## 2. 角色卡片

统一格式：**拥有**（可自由改的类型）/ **输入**（开工前必须拿到）/ **必须产出** / **禁止** / **验收人**。

### 2.1 底层 base Module 负责人

| 项 | 内容 |
|---|---|
| 拥有 | `:core` 全部；`:base` 全部；`libs.versions.toml` 的**基建类**条目 |
| 输入 | 各角色按 `BASE_MODULE_GUIDE.md` §9 提交的变更申请单 |
| 必须产出 | 基建 API 文档（`BASE_MODULE_GUIDE.md` 持续更新）+ 实现 + 对应单测 |
| 禁止 | ① 不做业务工具类（棋盘、房间、网络、序列化模型一律不进 base）② 不擅自改上层已消费的接口 ③ 打开 `dataBinding` / KSP |
| 验收人 | 架构师 |

> **本角色唯一有一票否决权的角色**。其他角色对 base 的任何诉求都必须走 §5 的 G1 门，不能"顺手加一个函数"。

### 2.2 架构设计师（兼集成协调）—— **当前身份：我**

| 项 | 内容 |
|---|---|
| 拥有 | 分层图、模块依赖方向、§3 的接口所有权表、所有 `.md` 架构文档、ADR 库、`settings.gradle.kts`、各模块 `build.gradle.kts` |
| 输入 | 各角色的越界申请、评审请求、里程碑验收请求 |
| 必须产出 | ① 分层图/依赖图 ② 接口签名草案 ③ ADR（每个决策一份）④ 接口所有权矩阵 ⑤ 评审结论（通过/驳回 + 理由）⑥ 联调准入判定 |
| 禁止 | ① **不写业务代码**（不实现 plugin、不写 socket、不写 UI）② 不绕过评审私自改协议 ③ 不替下游角色做产品决策 |
| 验收人 | 项目所有者（人类） |

### 2.3 服务发现与连接管理

| 项 | 内容 |
|---|---|
| 拥有 | `:net-android` 下 `nsd/`、`service/` 包全部；`:net-core` 的 `room/` 包；`RoomDiscovery` / `RoomAdvertiser` / `Advertisement` 的**实现** |
| 输入 | 架构师给出的 `RoomDescriptor` 字段定义 + 多候选地址策略；传输角色给出的"端口已 listen"回调契约 |
| 必须产出 | 发现层接口实现 + 多网卡候选地址方案 + **串行解析队列**设计 + 失败降级路径（TXT 兜底 / 手输 IP） |
| 禁止 | ① **不定义任何消息格式**（不碰 `Envelope`、不碰帧头）② 不建立 TCP 连接（只产出地址，连接是传输角色的事）③ 不在发现层做业务过滤以外的判断 |
| 验收人 | 架构师 |

### 2.4 传输与消息协议

| 项 | 内容 |
|---|---|
| 拥有 | `:net-proto` 的 `frame/`、`Envelope`、`MsgType`、`session/` 载荷包；`:net-core` 的 `transport/` 包；`Transport` / `Connection` / `CloseReason` / `FrameCodec` |
| 输入 | 架构师的 `Transport` / `Connection` 签名草案；游戏协议设计师给出的"载荷体积上限"需求 |
| 必须产出 | 协议规范（帧格式 + 类型表 + 版本策略）+ `FrameCodec` 实现 + `TcpTransport` 实现 + 心跳/超时机制 + **半包/粘包暴力切分测试** |
| 禁止 | ① **不改游戏语义**（不解释 `CMD`/`EVENT` 内容，只搬不解析）② 不实现重连策略（重连属于会话层，本角色只提供 `CloseReason`）③ 不让业务类型出现在 proto 的公开 API 上 ④ 不写 `net-core/session/`、不写 `net-proto/game/`（归游戏协议设计师） |
| 验收人 | 架构师 |

> **关键接缝**：`Envelope.payload` 是 `JsonElement`（不透明）。传输角色只保证"这段 JSON 完整送达"，游戏协议设计师只保证"这段 JSON 的内容有意义"。两边不需要开会就能并行。
>
> **模块骨架的例外授权**：本角色可代劳创建 `:net-proto` / `:net-core` 的目录与 `build.gradle.kts` **空白骨架**，前提是架构师已给定稿的 build 文件内容（依赖坐标、`api`/`implementation` 选择）并冻结。**骨架属于架构产物，填充属于本角色交付物**；`net-core/session/`、`net-core/room/`、`net-proto/game/` 的空包占位由架构师预置，本角色不得往里写实现。

### 2.5 游戏数据流/协议设计师

| 项 | 内容 |
|---|---|
| 拥有 | `:game-api` 全包的**提案权**；`GamePlugin` / `GameEvent` / `GameCommand` / `Seat` / `Seats`；`:net-core` 的 `session/` 包；`:net-proto` 的 `game/` 包（`SNAPSHOT` / `EVENT_BATCH` / `CMD` 载荷 schema 与语义） |
| 输入 | 架构师的 `GamePlugin` 泛型约束与"确定性义务"条款；传输角色的帧长上限 |
| 必须产出 | `GamePlugin` 接口签名 + 状态机图（大厅/对局/终局/掉线）+ 三类消息的语义与时序图 + 事件日志可回放格式 |
| 禁止 | ① **不写 UI**（不碰 View/Compose/VM）② 协议变更未过传输角色评审不得进入实现 ③ 不实现具体游戏的规则 ④ 不改 `Transport` / `Connection` / 帧格式 |
| 验收人 | 架构师（签名）+ 传输与消息协议（协议语义） |

### 2.6 交互/UI 流程设计师

| 项 | 内容 |
|---|---|
| 拥有 | `:app` 下 `ui/*/` 的**流程与状态绑定约定**文档；每个页面的 State / Event 契约 |
| 输入 | 会话层暴露的 `SessionState` 全集 + 一次性事件全集；架构师的"状态暴露约定"（`StateFlow` + `Channel`） |
| 必须产出 | UI 流程图（发现→加入→大厅→对局→异常）+ 每页 `UiState` / `UiEvent` 定义 + 异常与空态文案策略 |
| 禁止 | ① **不持有第二份权威状态**（不得缓存/镜像 `GameState`，只能持有会话层给的投影）② 不实现渲染与布局 ③ 不在 UI 层做重试/重连决策 |
| 验收人 | 架构师 |

### 2.7 数据流开发

| 项 | 内容 |
|---|---|
| 拥有 | `:game-<id>` 模块的实现（State / Event / Command / Serializer / reduce / validate） |
| 输入 | 已冻结的 `GamePlugin` 签名 + 事件与命令 schema + 事件回放工具 |
| 必须产出 | 可运行的插件 + 纯 JVM 单测（非法操作清单 + 重放一致性 + 序列化 round-trip） |
| 禁止 | ① **不碰 Transport / NSD / UI**（`:game-*` 里不允许出现 `java.net`、`android.*`、`com.poria.net.*`）② 不改 `GamePlugin` 签名 ③ 不在 `reduce` 里使用非确定性来源 |
| 验收人 | 游戏数据流/协议设计师 + 架构师（边界检查） |

### 2.8 交互开发

| 项 | 内容 |
|---|---|
| 拥有 | `:app` 下的 View / Adapter / 动画 / VM 实现 |
| 输入 | UI 流程设计稿 + `UiState`/`UiEvent` 定义 + 会话句柄的获取方式（`LanRuntime.currentRoom`） |
| 必须产出 | 可运行的界面 + 与设计稿一致的异常/空态表现 |
| 禁止 | ① **不实现游戏规则**（不判断胜负、不校验落子）② **不直连 Socket**（不允许 `import java.net`、不允许持有 `Connection`）③ 不自建第二个状态源 |
| 验收人 | 交互/UI 流程设计师 |

---

## 3. 接口所有权矩阵

**所有权 ≠ 审批权**。`所有者` 可以改实现、加测试、加内部类；`签名审批` 列才是"改了就得出 ADR"的人。

冻结级别：`FROZEN` = 签名改动需 ADR；`PROPOSED` = 改动需评审通过；`DRAFT` = 所有者内部自由，跨角色使用前须升到 PROPOSED。

| 类型 / 资源 | 所在模块 | 所有者 | 签名审批 | 冻结级别 | 允许的消费者 |
|---|---|---|---|---|---|
| `FrameHeader` / 帧格式常量 | `:net-proto` | 传输与消息协议 | 架构师 | FROZEN | proto、core |
| `Envelope` / `MsgType` | `:net-proto` | 传输与消息协议 | 架构师 | FROZEN | core、游戏协议（只读） |
| `FrameCodec` | `:net-proto` | 传输与消息协议 | 架构师 | FROZEN | core |
| `Transport` / `Listener` / `Connection` / `Endpoint` / `CloseReason` | `:net-core` | 传输与消息协议 | **架构师** | FROZEN | session、app（只读） |
| `HELLO` / `WELCOME` / `REJECT` / `PING` / `PONG` / `BYE` 载荷 | `:net-proto` | 传输与消息协议 | 架构师 | FROZEN | 发现（只读） |
| `CMD` / `CMD_RESULT` / `EVENT_BATCH` / `SNAPSHOT` / `SYNC_HASH` **消息类型与信封** | `:net-proto` | 游戏数据流/协议设计师 | 架构师 | FROZEN | core、app（只读） |
| 上述消息的**具体载荷字段**（schema 明细） | `:net-proto` | 游戏数据流/协议设计师 | 架构师 | **DRAFT** | — |
| `GamePlugin` / `GameEvent` / `GameCommand` | `:game-api` | 游戏数据流/协议设计师 | **架构师** | FROZEN | core、game-* |
| `Seat` / `Seats` / `PlayerMeta` / `RejectReason` | `:game-api` | 游戏数据流/协议设计师 | 架构师 | FROZEN | 全部上层 |
| `ValidateResult` | `:game-api` | 游戏数据流/协议设计师 | 架构师 | FROZEN | core、game-* |
| `SeededRandom` | `:game-api` | 游戏数据流/协议设计师 | 架构师 | FROZEN | game-* |
| `RoomDescriptor` / `HostAddress` / `RoomPhase` | `:net-proto` | **架构师** | 架构师 | FROZEN | 发现、session、UI（只读） |
| `RoomDiscovery` / `RoomAdvertiser` / `Advertisement` | `:net-core` | 服务发现与连接管理 | **架构师** | FROZEN | app |
| `SessionState`（两端状态机） | `:net-core` | 游戏数据流/协议设计师 | **架构师** | FROZEN | UI 设计师、交互开发 |
| `HostSession` / `GuestSession` 公开方法集 | `:net-core` | 游戏数据流/协议设计师 | **架构师** | FROZEN | app |
| `LanRuntime`（进程级句柄） | `:app` | 架构师 | 架构师 | FROZEN | 交互开发（只读） |
| `UiState` / `UiEvent`（每页） | `:app` | 交互/UI 流程设计师 | 架构师 | DRAFT | 交互开发 |

> **冻结依据**：`ARCHITECTURE.md` §12（项目所有者裁决：① 第一个游戏为棋类 ② §2 接口签名冻结 ③ `:net-core` 保持单模块）。
> **有意不冻结的两类**：载荷的具体字段（跟棋类规则一起长）、每页 `UiState`/`UiEvent`（随页面演进）。冻结它们等于冻结产品。
| `settings.gradle.kts` / `libs.versions.toml` | 根 / `gradle` | **架构师** | 架构师 | FROZEN | 全部（只读） |
| 各模块 `build.gradle.kts` | 各模块 | **架构师** | 架构师 | FROZEN | 全部（只读） |
| `:base` / `:core` 全部 | — | base 负责人 | base 负责人 | FROZEN | 全部 |

**使用规则**：`只读` 含义是"可以引用该类型、但不能新增字段/改签名/改语义"。需要新字段时，向所有者提请求，由所有者决定是否升 ADR。

**模块归属 vs 包归属（ADR-009）**：**模块是构建单元，归架构师；包是归属单元，按本表分配。** 同一模块内的不同包可以分属不同角色（例如 `:net-core` 的 `transport/` 归传输、`session/` 归游戏协议、`room/` 归发现）。

- 构建文件必须冻结的理由：`build.gradle.kts` 里的 `api` vs `implementation` 直接决定**下游能否看到某个类型**，这是分层可见性决策，不是实现细节。谁自由编辑 build 文件，谁就在事实上决定分层。
- 因此**创建模块的动作可以下放**（谁敲目录都行，纯机械劳动），但 `settings.gradle.kts` 的 `include`、各模块 build 文件的依赖坐标与可见性修饰符必须先由架构师定稿并冻结。
- 若某模块内的包归属冲突频发，升级方案是把它拆成两个模块，让边界变成编译期事实（ADR-009 备选 B，例：`:net-core` → `:net-transport` + `:net-session`）。

---

## 4. 交接契约

下游角色开工前必须收到下列输入，缺任何一项有权拒绝开工（并把这个拒绝记录成阻塞，而不是自己补设计）。

| 消费者 | 必须从上游拿到 | 缺了会发生什么 |
|---|---|---|
| 服务发现 | `RoomDescriptor` 字段定义 + 多候选地址策略 | 会自己造一套房间元模型，UI 和会话各认一套 |
| 传输协议 | `Transport`/`Connection` 签名 + 帧长上限 + 载荷体积需求 | 会顺手把业务字段塞进帧头，破坏"只搬不解析" |
| 游戏数据流/协议设计 | `GamePlugin` 泛型约束 + 确定性义务 + 重连语义（快照锚点） | 会设计出无法补发快照的协议，重连阶段返工 |
| 交互/UI 流程设计 | `SessionState` 全集 + 一次性事件全集 + 状态暴露约定 | 会在 UI 里自建状态机，出现第二份权威状态 |
| 数据流开发 | 已冻结的 `GamePlugin` + schema + 回放工具 | 会在实现期倒推接口，反过来改协议 |
| 交互开发 | 流程图 + `UiState`/`UiEvent` + `LanRuntime` 用法 | 会绕过会话层直连 socket 或自己重试 |

---

## 5. 评审门禁（四道，都很轻）

| 门 | 时机 | 执行者 | 通过标准 | 不通过的后果 |
|---|---|---|---|---|
| **G0 归属自检** | 动手前，5 分钟 | 改动者自己 | 填完 §7 审查模板前 3 条：我要改的类型都在 §3 里归我 | 停下来提申请，不要"先写再说" |
| **G1 接口评审** | 涉签名变更 | 架构师（+ 接口使用者） | §7 审查模板完整 + 有一个 ADR 或 ADR 豁免说明 | 驳回；不得进主干 |
| **G2 边界检查** | 提交前，自动 | CI / 任何人 | §6 四条机器规则全过 | 一律打回，不接受"这次特殊" |
| **G3 联调准入** | 里程碑 | 架构师 | 该里程碑的验收标准（见架构设计 §8 MVP 表）全绿 | 不许下游角色基于未验收的层开工 |

**G0/G2 由机器或自查完成，只有 G1 需要我出场。** 这是本流程唯一的成本，不要省。

---

## 6. 四条机器可查的边界规则

这四条取代所有"请不要"式的文字约束。**任一条失败，改动直接打回，不需要讨论。**

```bash
# R1 依赖方向：禁止反向依赖（settings.gradle.kts 的 include 顺序不能代替这个检查）
#     允许：app→net-android→net-core→net-proto→game-api；app→game-*→game-api；app→base→core
#     检查方式：gradle 的 project(":x") 依赖一旦反向，编译期就会失败 —— 靠 implementation/api 严格写

# R2 游戏插件不得接触网络与平台
grep -rn --include=*.kt -E "import (java\.net|javax\.net|android\.|com\.poria\.net)" game-*/
#     期望：无输出

# R3 UI 层不得直连传输层
grep -rn --include=*.kt -E "import com\.poria\.net\.core\.transport" app/
#     期望：无输出（只允许 import session 与 room 包）

# R4 插件不得使用非确定性来源
grep -rn --include=*.kt -E "import kotlin\.random|Math\.random|System\.currentTimeMillis" game-*/
#     期望：无输出（随机只能来自注入的 SeededRandom，且结果必须写进 Event）
```

> R2/R3/R4 建议直接做成 CI 的一个脚本或 gradle task，命名为 `checkBoundaries`。做到了，§2 里一半的"禁止事项"就不再需要人来记。

---

## 7. 审查模板（必填）

任何角色在提交改动（跨角色使用）之前，必须把下面 5 条填进 PR 描述或交付说明。这是本项目"防越界"的唯一强制流程。

```markdown
【角色】<本角色名>
【改动范围】<模块/包/类清单，一句话说明做什么>

1. 我改动或新增的类型（逐个列出）
   - <类型名> — 归属角色：<从 §3 查> — 冻结级别：<FROZEN/PROPOSED/DRAFT>

2. 我是否改动了不归我拥有的类型？
   - 是 → 附 ADR 编号 / 评审记录：
   - 否 →

3. 我新增的公开 API（签名原文）
   - <签名> — 消费者：<谁会用>

4. 边界检查结果
   - R1 依赖方向：通过 / 失败（说明）
   - R2 插件无网络：通过 / 失败
   - R3 UI 无传输直连：通过 / 失败
   - R4 无非确定性：通过 / 失败

5. 我**没有**做的部分（明确留给下游）
   - <内容> — 交给：<角色> — 它需要我先交付什么：
```

**第 2 条和第 5 条是这份模板的全部价值**：一条防越界，一条防遗漏。其余三条是形式。

---

## 8. ADR 库（架构师产出的唯一交付物）

| 编号 | 标题 | 状态 | 归档位置 |
|---|---|---|---|
| ADR-000 | UI 技术栈与角色表冲突（Compose vs viewBinding） | **已裁决：全 View** | 架构设计 §0 D1 |
| ADR-001 | 传输契约上移到"完整消息"，半包责任留在 Transport 内 | 已冻结 | 架构设计 §2.3 |
| ADR-002 | 抽 `RoomSession` 公共骨架，Host/Guest 不并列 | 已冻结 | 架构设计 §2.5 |
| ADR-003 | 会话状态放进程级单例，前台服务只做保活/通知 | 已冻结 | 架构设计 §5.2 / §6.2 |
| ADR-004 | 事件流 + 快照锚点 + 周期哈希（而非纯事件或纯快照） | 已冻结 | 架构设计 §6.1 |
| ADR-005 | `reduce` 纯函数 + 随机结果写入 Event | 已冻结 | 架构设计 §2.6 |
| ADR-006 | 帧格式：9 字节定长头 + JSON 载荷 + 256KiB 上限 | 已冻结 | 架构设计 §4.1 |
| ADR-007 | 版本策略三层（protoVersion / gameVersion / stateSchemaVersion） | 已冻结 | 架构设计 §4.4 |
| ADR-008 | 传输层实现必查项：`listen(0)`、专属 dispatcher、不用 base 的 ThreadPool | 已冻结 | 架构设计 §2.3 |
| ADR-009 | 模块是构建单元（归架构师，build 文件冻结），包是归属单元（按 §3 分配）；创建动作可下放 | **已裁决：采用，`:net-core` 不拆** | 架构设计 §12 |
| ADR-010 | 隐藏信息（牌类）需把 `EVENT_BATCH` 改为 per-player 裁剪 | **已否决（不适用）**：第一个游戏为棋类，公开信息模型成立 | 架构设计 §12.1 |
| ADR-011 | 自研 `ValidateResult` 替代 Arrow `Either` | **已定稿** | 架构设计 §12.2 |
| ADR-012 | `GuestSession` 依赖插件实例而非插件 ID（D5 要求客端真能推演） | **已定稿** | 架构设计 §12.2 |

> **我今后的交付形态** = 新增/更新 ADR + §3 矩阵的行 + §2 角色卡片的边界修正。不交付代码。

---

## 9. 角色 × 模块 × 目录 映射

| 角色 | 可写目录 | 只读目录 |
|---|---|---|
| base 负责人 | `core/`、`base/` | 其余全部 |
| 架构师 | `docs/`、`settings.gradle.kts`、各模块 `build.gradle.kts`、`gradle/libs.versions.toml` | 全部源码 |
| 服务发现与连接管理 | `net-android/.../nsd/`、`net-android/.../service/`、`net-core/.../room/` | 其余全部 |
| 传输与消息协议 | `net-proto/`（除 `game/` 包）、`net-core/.../transport/` | 其余全部 |
| 游戏数据流/协议设计师 | `game-api/`、`net-core/.../session/`、`net-proto/.../game/` | `:app` |
| 交互/UI 流程设计师 | `app/.../ui/*/`（仅 State/Event 定义与流程文档） | 其余全部 |
| 数据流开发 | `game-<id>/` | 其余全部 |
| 交互开发 | `app/src/main/res/`、`app/.../ui/*/`（View/VM 实现） | 其余全部 |

---

## 10. 待决问题（阻塞项）

| # | 问题 | 阻塞谁 | 需要的裁决 |
|---|---|---|---|
| 1 | ~~UI 技术栈（Compose vs View）~~ | — | **已裁决：View + viewBinding**（ADR-000），§2.8 已按此修正 |
| 2 | `RoomDescriptor` 归属：同时被发现层、会话层、UI 消费 | 服务发现、UI 设计师 | 已定为架构师所有（§3），确认无异议即可解阻 |
| 3 | ~~第一个游戏插件选型~~ | — | **已裁决：棋类（五子棋）**，公开信息模型成立，ADR-010 否决 |
| 4 | 是否允许角色合并（一人戴多顶帽子） | 全部 | 建议允许，但**硬边界不可合并**：见 §11 |
| 5 | ~~§2 接口签名是否冻结为 `FROZEN`~~ | — | **已裁决：冻结**（ADR-011 / ADR-012 两条修正已定稿，见 `ARCHITECTURE.md` §12） |
| 6 | 骨架已定稿，谁先开工 | 全部实现角色 | 按 `ARCHITECTURE.md` §8：**传输与消息协议先做 M0**（`:net-proto` 帧 + `:net-core/transport`），冻结 `Envelope` 后其他角色方可并行 |

---

## 11. 角色合并建议（现实约束）

八个角色不必是八个 agent。合并规则只有一条：

> **可以合并"产出物"，不可以合并"归属权"。**

| 合并方案 | 可合并的帽子 | **不可合并**（必须保持隔离） |
|---|---|---|
| 2 人 | base 负责人 + 架构师；传输 + 游戏协议 | 架构师 与 任何"实现者"帽子（评审自己写的代码等于没评审） |
| 3 人 | 以上 + 发现 + UI 设计 | 数据的"作者"与"审查者" |
| 4 人以上 | 按 §2 一人一帽 | — |

最小可行配置（推荐给你现在的形态）：**架构师（我）+ base 负责人 + 一个"实现"角色轮换戴传输/插件/UI 的帽子**。但每次换帽子必须重新填 §7 模板，且换帽子后的第一件事是跑 §6 的四条检查。

---

## 附录：本文档与 `BASE_MODULE_GUIDE.md` 的边界

- base 契约管 **`:base`/`:core` 内部**的用法与禁止项 → 冲突时 base 契约优先。
- 本文档管 **上层角色之间**的归属与流程 → 不与 base 契约重叠。
- 本文档不定义"协议长什么样"和"界面长什么样"——那是 `ARCHITECTURE.md` 与下游设计文档的事。
