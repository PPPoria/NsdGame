# 茯苓游戏（NsdGames）

单人 Android 小游戏集合。**第一个游戏是单机 UNO**（1 个真人 + 3 个电脑）。

设计目标只有一个：**一个人一个周末能看懂、改得动、测得出**。
所以模块就三个、规则就一个文件、界面就一张画布，没有 DI / EventBus / Repository / UseCase。

```
core/                     纯 Kotlin 游戏内核，零 Android 依赖
  src/main/kotlin/com/poria/uno/
    Card.kt               UnoColor / UnoKind / UnoCard + 108 张牌的整副牌
    UnoState.kt           一局游戏的完整状态（不可变）+ isPlayable
    UnoAction.kt          Play / Draw / Pass + IllegalMove
    UnoEngine.kt          ★ 全部规则：出牌合法性、跳过/反转/罚牌、摸牌、洗牌重发、胜负
    UnoAi.kt              电脑玩家（贪心，不搜索）
  src/test/kotlin/com/poria/uno/
    UnoEngineTest.kt      规则单测 + AI 自对弈一整局

base/                     Android 基础设施（可复用的，跟游戏无关）
  BaseActivity/BaseFragment/BaseDialog、dp2px、防抖点击、DSManager、ThreadPool

app/                      唯一的应用模块
  src/main/java/com/ppporia/nsdgames/
    ui/MenuActivity.kt    首页
    ui/UnoActivity.kt     对局页：把状态画出来、把点击变成动作、显示错误提示
    ui/UnoTableView.kt    ★ 牌桌自绘（Canvas + onTouchEvent，不用 RecyclerView）
    session/GameSession.kt 状态持有 + 转发动作 + 跑电脑；StateFlow 给 UI 订阅
    net/README.md         联网占位，现在是空的
```

依赖方向：`:app` → `:base` → `:core`。反向依赖编译不过。

## 跑起来

```bash
./gradlew :core:test          # 规则单测，几秒跑完，不需要模拟器
./gradlew :app:assembleDebug  # 打包 APK
```

## 想改东西时改哪个文件

| 想改什么 | 改这里 |
|---|---|
| 规则（能不能出、跳过反转、罚几张） | `core/.../UnoEngine.kt` |
| 状态里有哪些字段 | `core/.../UnoState.kt` |
| 电脑怎么出牌 | `core/.../UnoAi.kt` |
| 牌桌长什么样 | `app/.../ui/UnoTableView.kt` |
| 点了牌之后干什么 | `app/.../ui/UnoActivity.kt` |
| 本地打还是联网打 | `app/.../session/GameSession.kt` |

## 架构硬约束（以后让 AI 写代码时把这段贴给它）

单人项目，边界由人定，AI 只填实现。**以下每一条都是"不许"**：

1. 只有 `:core` / `:base` / `:app` 三个模块，**禁止新增模块**。
2. **禁止新增依赖**。现在只有 AndroidX 基础库、Material、kotlinx-coroutines。
3. 禁止 Hilt / Koin / Dagger、RxJava、EventBus、Room、Navigation。
4. 禁止"只有一个实现的接口"、禁止 UseCase / Repository / Mapper 分层、禁止自己写 DI 容器。
5. 规则判定只能写在 `:core`。UI 或 session 里出现"这张牌能不能出"的判断就是 bug——
   应该问 `UnoState.isPlayable` 或让 `UnoEngine.reduce` 抛 `IllegalMove`。
6. 新文件先按 200 行估一遍，超了就想想是不是在造框架。
7. 每个功能一次做完再做下一个，不要预留"以后可能要"的扩展点。

审查时只问四句话：这个抽象有第二个实现吗？这个类能一句话说清职责吗？新增依赖了吗？
规则判定有没有散到 UI / 网络里？

## 当前刻意简化的地方

- UNO 规则：不喊"UNO"罚牌、不做 +4 质疑、不计分、不打多局。
- 界面：自绘无动画、无音效、手牌不拖拽（点一下出牌）。
- 转屏靠 `android:configChanges` 不重开；真要横竖屏两套布局时再把状态搬进 ViewModel。
- 联网：见 `app/src/main/java/com/ppporia/nsdgames/net/README.md`。

## 版本管理约定

依赖坐标和版本只写在 `gradle/libs.versions.toml`，模块里不允许出现带版本号的依赖。
插件版本在根 `build.gradle.kts` 用 `apply false` 声明。
