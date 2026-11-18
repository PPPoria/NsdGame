# NsdGames · base 模块使用契约（给上层 AI 的说明书）

> **本文档的读者**：负责实现 `:app` 与业务模块（玩法、UI、数据）的 AI。
> **本文档的性质**：`:core` + `:base` 的**唯一权威契约**。其中的签名、时序、禁止项均与当前代码一致（JVM 目标 Java 17，minSdk 31 / targetSdk 36 / compileSdk 36）。
> **本文档的维护者**：负责 `:core` / `:base` 的 AI。上层发现问题请走第 9 节流程，不要直接改基础模块。

---

## 0. 四条铁律

| # | 规则 | 原因 |
|---|---|---|
| 1 | **只用不改**：你可以调用 base 的 API，但不要修改 `:base` / `:core` 里的源码 | 两个模块被所有上层共享，改动影响面不可控；且其中很多实现细节是踩坑后的修复 |
| 2 | **业务不进 base**：玩法、棋盘、存档模型、具体页面、网络接口都不准放进 `:base` | base 只提供与业务无关的基础设施 |
| 3 | **禁止项都是回归项**：本文标注「禁止」的写法，都是曾经真实存在并已修复的 bug，不要"顺手优化"回去 | 见第 6 节对照表 |
| 4 | **有基础设施级需求先申请**：base 缺能力时，先在业务模块里自己实现；确认是通用需求后按第 9 节登记 | 避免 base 被临时需求污染 |

---

## 1. 模块划分与依赖方向

```
:app  (android-application)   壳工程：Manifest、主题、Application、各页面
  ↓ implementation
:base (android-library)       UI 基类 / 扩展 / 键值存储 / 线程池 / StrictMode
  ↓ api
:core (kotlin-jvm, 纯 Kotlin) 时间换算 / HSL 颜色 / 哈希 —— 零 Android 依赖
```

| 模块 | 你能改吗 | 说明 |
|---|---|---|
| `:app` | ✅ 随便改 | 你的主战场 |
| 将来的 `:game` / `:feature-*` | ✅ 可以新建 | 需在 `settings.gradle.kts` 里 `include`，并在 `:app` 里依赖 |
| `:base` | ❌ 不改 | 需要改 → 第 9 节 |
| `:core` | ❌ 不改 | 同上 |

---

## 2. 环境与构建

### 2.1 工具链

| 项 | 值 |
|---|---|
| JDK | **17+**（这台机器用 Android Studio 自带 JBR 21；如果 `JAVA_HOME` 没配，命令行构建会失败，先 `$env:JAVA_HOME="C:\Users\ADMIN\AppData\Local\Programs\Android Studio\jbr"`） |
| Gradle | 8.13（wrapper 已指向官方 https 地址，不要改回本地路径） |
| AGP / Kotlin | 8.11.2 / 2.0.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 31 |
| Java 源/目标 | 17 |
| 已启用 | `viewBinding`、`buildConfig`、`parallel`、`build cache` |
| 已关闭 | `dataBinding`、KSP（**不要擅自打开**，构建会明显变慢） |

### 2.2 常用命令

```bash
./gradlew :core:test            # 纯逻辑单测，秒级，改工具函数后必跑
./gradlew :app:assembleDebug    # 打包
./gradlew :base:lintDebug :app:lintDebug   # 静态检查，期望 "No Issues Found"
```

### 2.3 版本管理规则

- 所有依赖坐标/版本**只在** `gradle/libs.versions.toml` 里；模块脚本里**不允许**出现带版本号的依赖（`"androidx.xxx:1.2.3"` 这种写法会被打回）。
- 加依赖的姿势：先在 catalog 里加条目 → 再在模块里用 `libs.xxx.yyy`。
- 插件版本只在根 `build.gradle.kts` 用 `alias(...) apply false` 声明，模块里 `alias(libs.plugins.xxx)` 应用。
- 库模块（如果你新建了 `:feature-xxx`）里：出现在 **public API 签名上**的类型用 `api(...)`，纯实现细节用 `implementation(...)`。

---

## 3. API 契约

### 3.1 Application —— `com.poria.base.BaseApp`

```kotlin
// app/src/main/java/.../App.kt
class App : BaseApp()
```
```xml
<!-- AndroidManifest.xml -->
<application android:name=".App" ... >
```

| 成员 | 说明 |
|---|---|
| `BaseApp.app: BaseApp` | 全局 Application 实例 |
| `BaseApp.context: Context` | `applicationContext`，**优先用它**，不要传 Activity |
| `BaseApp.isInitialized: Boolean` | 是否已初始化，库代码兜底判断用 |

会自动做两件事：`DSManager.init(this)`、debug 构建下 `StrictModeConfig.enable()`。

> 如果宿主坚持用自己的 Application 而不继承 `BaseApp`，**必须**在 `onCreate` 里手动调用 `DSManager.init(this)`，否则第一次用 `DSManager` 会抛带指引文案的异常。

---

### 3.2 Activity 基类 —— `com.poria.base.ui.BaseActivity`

```kotlin
abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity()
```

#### 必须实现

| 成员 | 说明 |
|---|---|
| `abstract fun onCreateBinding(): VB` | 通常 `XxxBinding.inflate(layoutInflater)` |

#### 可重写的钩子

| 成员 | 时机 | 用途 |
|---|---|---|
| `open fun initView()` | `onCreate` 内，**同步一次** | 初始化 View 状态（设置文本、可见性、列表 etc.） |
| `open fun initListener()` | `onCreate` 内，**同步一次** | 挂点击/监听 |
| `open suspend fun loadData()` | `repeatOnLifecycle(STARTED)` 内 | 数据加载，可直接 await。**每次回到前台都会执行一次** |
| `open suspend fun bindUi()` | `repeatOnLifecycle(STARTED)` 内，紧跟 `loadData` | 收集 Flow / 订阅状态，退后台自动取消 |
| `open val insetTarget: View` | 初始化时 | 系统栏内边距施加到哪个 View，默认 `binding.root` |
| `open fun onWindowInsetsChanged(insets: WindowInsetsCompat)` | insets 每次变化 | 需要额外处理刘海/输入法时用 |

#### 可直接使用的属性/方法

| 成员 | 说明 |
|---|---|
| `protected val binding: VB` | 视图绑定，**只在 `onCreate` 之后、`onDestroy` 之前有效** |
| `protected val systemBarPadding: IntArray` | `[left, top, right, bottom]`，insets 未分发时全为 0 |
| `protected fun setLightStatusBar(isLight: Boolean = true)` | 状态栏图标深浅 |
| `protected fun setLightNavigationBar(isLight: Boolean = true)` | 导航栏图标深浅 |
| `protected fun setImmersiveLayout(isImmersive: Boolean = true)` | 全屏/恢复，和 padding 逻辑联动，可反复切换 |

#### 生命周期时序（务必按这个写代码）

```
onCreate:
  super.onCreate
  enableEdgeToEdge()                       ← base
  onCreateBinding() → setContentView       ← base 调你的
  initEdgeToEdge()                         ← base 注册 insets 监听
  initView()                               ← 你的，同步
  initListener()                           ← 你的，同步
  lifecycleScope.launch { repeatOnLifecycle(STARTED) { loadData(); bindUi() } }
                                           ← 你的，异步，可挂起
onDestroy:
  _binding = null                          ← base，之后访问 binding 会抛可读异常
```

#### base 已经替你处理的 edge-to-edge

- 已调用 `enableEdgeToEdge()`，**不要再调一次**。
- 系统栏 insets 会**叠加**到 `insetTarget` 原有的 padding 上（不会把你的 padding 清零）。
- **不要**自己给 `binding.root` 再 `ViewCompat.setOnApplyWindowInsetsListener`，那会覆盖 base 的监听。需要额外处理请重写 `onWindowInsetsChanged`。

#### 禁止

```kotlin
// ❌ 已废弃，targetSdk 36 下无效
window.statusBarColor = Color.TRANSPARENT
window.navigationBarColor = ...
window.decorView.systemUiVisibility = ...

// ❌ 不要硬编码 root id 再手动套 insets
ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { ... }

// ❌ 不要在字段初始化器里访问 binding（此时 onCreate 还没跑）
private val title = binding.tvTitle.text

// ❌ 不要为了"一次性加载"而在 loadData 里假定它只执行一次
```

---

### 3.3 Fragment 基类 —— `com.poria.base.ui.BaseFragment`

```kotlin
abstract class BaseFragment<VB : ViewBinding> : Fragment()
```

| 成员 | 说明 |
|---|---|
| `abstract fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?): VB` | 通常 `XxxBinding.inflate(inflater, container, false)` |
| `protected val binding: VB` | 只在 `onViewCreated` → `onDestroyView` 之间有效 |
| `initView()` / `initListener()` | `onViewCreated` 内**同步一次** |
| `loadData()` / `bindUi()` | `viewLifecycleOwner.repeatOnLifecycle(STARTED)` 内 |

- binding 在 `onDestroyView` 被置空：Fragment 在 back stack 中存活但 View 已销毁时，访问 `binding` 会立刻抛出可读异常，而不是静默引用旧 View 树。
- 数据逻辑**写在 Fragment 自己身上**（`loadData` / `bindUi`），**不要**让 Activity 通过 tag 找到 Fragment 再反向调用它的方法。

#### 禁止

```kotlin
// ❌ by lazy 缓存 binding：会泄漏 View，且重新 attach 时拿到已销毁的 root
val binding by lazy { FragmentHomeBinding.inflate(layoutInflater) }

// ❌ 用 tag 字符串由 Activity 驱动 Fragment
val baseFragmentTagList = listOf("home", "shop")
supportFragmentManager.findFragmentByTag("home") as BaseFragment<*>
```

---

### 3.4 Dialog 基类 —— `com.poria.base.ui.BaseDialog`

```kotlin
abstract class BaseDialog<VB : ViewBinding> : DialogFragment()
```

| 成员 | 说明 |
|---|---|
| `abstract fun onCreateBinding(inflater, container): VB` | 同上 |
| `protected val binding: VB` | 只在 `onViewCreated` → `onDestroyView` 之间有效 |
| `initView()` / `initListener()` | `onViewCreated` 内**同步一次**（不要放进协程，否则数据一挂起就是白屏） |
| `loadData()` / `bindUi()` | `viewLifecycleOwner.repeatOnLifecycle(STARTED)` |
| `protected open fun initWindowInsets(window: Window, gravity: Int = Gravity.BOTTOM, width: Int = MATCH_PARENT, height: Int = WRAP_CONTENT)` | 在 `onStart` 调用。想做居中/固定宽高/特殊遮罩就重写它 |

#### 禁止

```kotlin
// ❌ 把 UI 初始化排在 suspend 调用之后 —— 数据没回来时界面是空的
lifecycleScope.launch {
    loadData()      // 一旦挂起（网络/IO），下面三行永远不执行
    initView()
    initListener()
}
```

---

### 3.5 键值存储 —— `com.poria.base.store.DSManager`

```kotlin
object DSManager
```

| 方法 | 说明 |
|---|---|
| `fun init(context: Context)` | 幂等；`BaseApp` 已自动调用 |
| `suspend fun setBoolean/setInt/setLong/setFloat/setDouble/setString(key: String, value: ...)` | 写入（`edit` 是全文件重写，见下方限制） |
| `fun getBoolean/getInt/getLong/getFloat/getDouble/getString(key: String, defaultValue: ...): Flow<T>` | 读取，**已做 `distinctUntilChanged`** |
| `suspend fun remove(key: String)` | 删除该 key 的所有类型变体 |
| `suspend fun clear()` | 清空全部（"恢复默认设置"用） |

```kotlin
// 读：在 bindUi 里 collect
override suspend fun bindUi() {
    DSManager.getInt(Keys.HIGHEST_SCORE, 0).collect { score -> binding.tvScore.text = score.toString() }
}

// 写：在 viewModelScope / lifecycleScope 里
lifecycleScope.launch { DSManager.setInt(Keys.HIGHEST_SCORE, 120) }
```

**限制（重要）**

- 只适合**低频配置**（开关、最高分、上次选择）。DataStore 每次 `edit` 重写整个文件。
- **不要**用来存整份存档 / 大 JSON / 每帧变化的数值。存档请自己在业务模块建 Repository 写文件。
- `Double` 实际存在 `longPreferencesKey` 的位模式里：**同一个 key 不要既当 Long 又当 Double**。
- key 建议集中成常量对象，不要散落裸字符串（拼错不报错）。

> base **不**提供序列化能力（`kotlinx-serialization` 未在 base 声明）。要做 JSON 存档，在你的业务模块里加：
> ```kotlin
> // build.gradle.kts
> plugins { alias(libs.plugins.kotlin.serialization) }
> dependencies { implementation(libs.kotlinx.serialization.json) }
> ```
> 插件与库的版本条目已经在 `gradle/libs.versions.toml` 里备好，直接引用即可。

---

### 3.6 线程池 —— `com.poria.base.util.ThreadPool`

```kotlin
object ThreadPool {
    val dispatcher: CoroutineDispatcher   // pool.asCoroutineDispatcher()
    fun execute(task: Runnable)
}
```

- 配置：core 2 / max 4 / 有界队列 64 / 空闲 30s 回收 / 线程名 `nsd-worker-N`。
- 协程写法优先：`withContext(ThreadPool.dispatcher) { ... }`。
- `execute()` 用的是 `AbortPolicy`：队列满会抛 `RejectedExecutionException`，别再往里塞不设上限的任务。
- 纯 CPU 计算用 `Dispatchers.Default`，普通磁盘 IO 用 `Dispatchers.IO`；`ThreadPool` 用于**想独占队列、便于抓 Systrace 的后台任务**。

---

### 3.7 StrictMode —— `com.poria.base.util.StrictModeConfig`

- `fun enable()`，只被 `BaseApp` 在 `BuildConfig.DEBUG` 下调用，只有 `penaltyLog()`。
- **禁止**加 `penaltyDeath()` / `penaltyDialog()`：主线程任何一次磁盘读写都会杀进程。

---

### 3.8 扩展函数

```kotlin
// com.poria.base.ext
fun Context.dp2px(dp: Float): Int
fun Context.dp2px(dp: Int): Int
fun Context.sp2px(sp: Float): Int

fun View.setOnClickListenerWithClickAnimation(
    duration: Long = 50L,
    debounceMillis: Long = 0L,
    listener: (View) -> Unit
)

fun View.setOnDebouncedClickListener(
    intervalMillis: Long = 500L,
    listener: (View) -> Unit
)
```

| 注意点 | 说明 |
|---|---|
| 尺寸换算 | 走 `Context.dp2px(...)`（用的是 `resources.displayMetrics`），**不要**用 `Resources.getSystem()`，那会忽略多窗口/分屏/字体缩放 |
| 按压动画 | 会覆盖 View 现有的 `OnTouchListener`；`ACTION_CANCEL` 也会复原，列表里滑动不会卡在缩小态 |
| 防抖 | 所有"点一下就跳转/扣资源/弹窗"的按钮都加上（默认 500ms），连点是游戏里最常见的 bug |

---

### 3.9 纯函数工具 —— `com.poria.core`

`:core` 零 Android 依赖，可在 JVM 上直接单测。

**时间**

```kotlin
fun Long.toDateFormat(zone: ZoneId = ZoneId.systemDefault()): DateFormat
fun Long.toDayOfWeek(zone: ZoneId = ZoneId.systemDefault()): Int   // 周一=1 … 周日=7

data class DateFormat(
    var year: Int, var month: Int, var day: Int,
    var hour: Int = 0, var minute: Int = 0, var second: Int = 0
) {
    fun toTimeStamp(zone: ZoneId = ZoneId.systemDefault()): Long
    override fun toString(): String   // "2024-1-5" / "2024-1-5 8:30" / "2024-1-5 8:30:15"
}
```

**颜色**

```kotlin
fun hslToColor(h: Float, s: Float, l: Float): Int          // h∈[0,360) s,l∈[0,1]，返回 0xAARRGGBB
fun Any.genColor(hOffset: Int = 0, sBase: Int = 60, lBase: Int = 85): Int   // 同一对象稳定同色
fun argbOf(r: Float, g: Float, b: Float, a: Float = 1f): Int
```

**哈希**

```kotlin
fun Any.scrambleHash(): Int   // MurmurHash3 finalizer
```

> ⚠️ `scrambleHash()` / `genColor()` 只保证「同一进程内、同一对象内容」稳定。**不要**把它当持久化 ID 写进存档 —— 存档 ID 请显式给每个实体一个字段。
> ⚠️ 所有带 `zone` 参数的函数：测试与跨时区同步时**显式传** `ZoneId`，否则结果依赖设备时区。

---

## 4. 资源契约

### 4.1 主题链

```xml
<!-- base -->
<style name="Theme.Base" parent="Theme.Material3.DayNight.NoActionBar" />
<!-- app -->
<style name="Theme.NsdGames" parent="Theme.Base" />
```

- **base 不提供完整 app 主题**，只提供基座。要改主色/背景，在 `:app` 的 `Theme.NsdGames` 里加 `<item>`。
- 暗色：`DayNight` 自动跟随系统，**不需要**新建 `values-night/themes.xml`（除非要定制暗色值）。
- 不要新建一套 `Base.Theme.xxx` 平行主题；`Theme.Base` 是唯一父级。

### 4.2 尺寸（`base/src/main/res/values/dimens.xml`）

| 名称 | 值 | 用途 |
|---|---|---|
| `space_xs` / `space_s` / `space_m` / `space_l` / `space_xl` | 4 / 6 / 10 / 20 / 30 dp | 间距 |
| `corner_s` / `corner_m` / `corner_l` | 6 / 12 / 24 dp | 圆角（与 drawable 对应） |
| `text_caption` / `text_label` / `text_body` / `text_subtitle` / `text_title` | 11 / 15 / 19 / 23 / 29 sp | 字号 |
| `elevation_low` / `elevation_high` | 4 / 8 dp | 抬升/投影 |

规则：**布局里不要写 `12dp` / `16sp` 这类裸数字**，用上面的名字。确实需要新尺寸时优先复用；真的不够再加到 `:app` 的同名 dimens 里（`values-sw600dp` 等限定符放 `:app`，不要动 base）。

### 4.3 圆角背景（`base/src/main/res/drawable/`）

| 资源 | 说明 |
|---|---|
| `@drawable/bg_corner_6` / `bg_corner_12` / `bg_corner_24` | 四角圆角 |
| `@drawable/bg_corner_pill` | 胶囊 |
| `@drawable/bg_corner_top_24` | 只有上圆角（底部弹窗） |
| `@drawable/bg_corner_bottom_24` | 只有下圆角 |

底色都是 `?attr/colorSurface`，**暗色模式自动适配**。
❌ **禁止**新建写死 `#FFFFFF` 的 shape —— 暗色下就是一块白砖。需要别的底色时用 `<solid android:color="?attr/xxx"/>` 或 `@color/xxx`。

### 4.4 字符串

- **base 不提供任何 string 资源**，所有文案在 `:app` / 业务模块的 `strings.xml`。
- 代码里出现中文字面量会被 lint 报 `SetTextI18n`，一律走 `@string` / `getString(...)`。

### 4.5 资源引用注意（`android.nonTransitiveRClass=true`）

- **XML 里**可以直接引用 `@dimen/text_body`、`@drawable/bg_corner_12`（资源会合并）。
- **Kotlin 代码里**要用 `com.poria.base.R.dimen.text_body`，`:app` 的 `R` 里没有 base 的资源。更好的做法是**不要在代码里读 base 的资源**，改用 `context.dp2px(...)` 或直接让 XML 用。

---

## 5. 上层实现规范（写代码前先过一遍）

1. **每个页面配自己的 ViewModel**（这比泛型 VM 更稳、更好读）：
   ```kotlin
   class HomeActivity : BaseActivity<ActivityHomeBinding>() {
       private val vm: HomeViewModel by viewModels()
       override fun onCreateBinding() = ActivityHomeBinding.inflate(layoutInflater)
       override suspend fun bindUi() { vm.state.collect { render(it) } }
   }
   ```
   ❌ 禁止重新引入"万能单值 VM"（历史上的 `SingleVM<T>`，已删除）：它的初始值取法会 NPE、没有事件通道、没有 `SavedStateHandle`。
2. **状态用 `MutableStateFlow`，一次性事件用 `Channel`**：
   ```kotlin
   private val _events = Channel<UiEvent>(Channel.BUFFERED)
   val events = _events.receiveAsFlow()   // 不会重复消费
   ```
3. **列表用 `ListAdapter` + `DiffUtil.ItemCallback`**，❌ 不要 `notifyDataSetChanged()`（丢动画、丢局部刷新，棋盘/背包会卡）。`:base` 不提供任何 Adapter 基类；需要 `androidx.recyclerview:recyclerview` 时自己加到 `:app` 的依赖（并先在 catalog 加条目）。
4. **协程作用域**：`viewModelScope`（VM 内）、`lifecycleScope`（页面内）、base 钩子里已经包了 `repeatOnLifecycle`，**不要在 `initView` 里 `launch` 一个不受生命周期管理的协程**。
5. **网络/数据库/DI/日志/路由**：base **不提供**，由你自己在 `:app` 或独立模块里选型实现，不要塞进 base。推荐：Retrofit/OkHttp、Room、Hilt/Koin、Timber、Navigation 或自研 Router。
6. **存档**：用 Repository + 文件序列化（`kotlinx-serialization-json`，见 3.5 节末尾的接入方式）；`DSManager` 只放配置项。
7. **游戏逻辑**：未来的 `:game` 模块（棋盘、存档、音效池、渲染循环）不属于 base，也不要在 base 里加 `BaseSurfaceView`。

---

## 6. 常见错误对照表

| ❌ 错误写法 | ✅ 正确写法 | 为什么 |
|---|---|---|
| `val binding by lazy { ... }`（Fragment/Dialog） | 用 base 的 `binding`（内部自动置空） | lazy 会泄漏 View；重新 attach 时拿到已销毁的 root → `IllegalStateException` |
| 把 `initView()` 放进 `lifecycleScope.launch { loadData(); initView() }` | `initView()` 留在同步钩子 | 数据一挂起就是白屏 |
| Activity 用 tag 找 Fragment 反向调方法 | Fragment 自己 `loadData`/`bindUi` | tag 字符串无编译期校验；职责错位 |
| `getItemId()` 返回 `hashCode()` | 显式稳定的 id，或 `setHasStableIds(false)` | hashCode 撞车会让 RecyclerView 抛 `Two different ViewHolders have the same stable ID` |
| data class 手写 `hashCode() = javaClass.hashCode()` | 交给 data class 自动生成 | 所有实例 hash 相同 → HashMap/HashSet 退化成链表 |
| `setDouble` 用 float 存 | 用 `DSManager.setDouble`（long 位模式） | 精度丢失 |
| `dp2px` 用 `Resources.getSystem()` | `context.dp2px(...)` | 忽略多窗口/字体缩放 |
| `statusBarColor` / `systemUiVisibility` | `enableEdgeToEdge()` + `WindowInsetsControllerCompat`（base 已封装） | targetSdk 36 下已失效 |
| StrictMode 加 `penaltyDeath()` | 只用 `penaltyLog()`（base 已配好） | 主线程一写磁盘就杀进程 |
| `ThreadPoolExecutor` core=max=核数 + 无界队列 | base 的有界配置，或直接用 `Dispatchers` | 无界队列下 max/keepAlive 全是死配置 |
| 把配置 key 写成散落字符串 | 集中到 `object Keys` | 拼错不报错 |
| `android:text="确定"` | `@string/confirm` | lint `SetTextI18n`；后续要出多语言 |

---

## 7. base 的已知边界（别指望它，也不要硬塞进来）

| 没有 | 你该怎么做 |
|---|---|
| DI、日志、路由、网络、数据库 | 在 `:app`/独立模块选型实现 |
| 列表适配器基类 | 自己写 `ListAdapter` + `DiffUtil` |
| ViewModel 基类 / 状态容器 | 每个页面一个 VM + `StateFlow` |
| 游戏循环 / SurfaceView / 音效 / 对象池 | 未来的 `:game` 模块 |
| 平板/横屏专属尺寸 | 在 `:app` 加 `values-sw600dp`、`values-land` |
| 权限申请、Toast、Loading 封装 | 自己写（基础模块暂不提供，需要时走第 9 节） |
| `<merge>` 根布局支持 | `ViewBinding.inflate(inflater, container, false)` 不支持 merge；改用普通根布局 |

其它需要知道的语义：

- `loadData()` **每次回到前台都会执行**（这是 `repeatOnLifecycle(STARTED)` 的语义，等价于原来"onRestart 刷新"的意图）。只要首次加载：在 `onCreate` 里自己做，或用 `var loaded = false`。
- `binding` 访问时机错了会抛 `IllegalStateException` 并带中文指引，这是**预期行为**，不要改成 `lateinit` 绕过。
- `systemBarPadding` 在 insets 未分发时是 `0`，不要假定它有值。

---

## 8. 提交前自检

```bash
./gradlew :core:test :app:assembleDebug :base:lintDebug :app:lintDebug
```

| 检查项 | 期望 |
|---|---|
| 构建 | `BUILD SUCCESSFUL` |
| `:core:test` | 17 个用例全过（改 `:core` 的工具函数后必须加用例） |
| lint | 两个模块都是 **No Issues Found**；新增告警必须处理掉，不要 `@Suppress` 掩盖 |
| 依赖坐标 | 模块脚本里没有裸版本号 |
| 文案 | 没有硬编码中文字符串 |
| 尺寸 | 布局里没有裸 `12dp`/`16sp`（用 `@dimen`） |
| 颜色 | 没有写死 `#FFFFFF` 的 shape，改主题色请用 `?attr/` |

代码风格：Kotlin official style、4 空格缩进、**中文 KDoc**（public API 必须写「为什么」和「限制」，不要只写「做了什么」）。

---

## 9. 变更请求流程（base 需要改时）

**不要自己改 `:base` / `:core`。** 按下面模板提给负责 base 的 AI：

```
【需要的能力】一句话描述
【为什么必须放 base】说明它跨页面/跨模块复用，而不是单个页面的需求
【期望签名】fun xxx(a: A): B
【影响面】哪些已有 API 会变；是否需要迁移
【是否破坏兼容】是 / 否
```

允许你直接做、不需要申请的：

- `:app` 里的一切（Manifest、主题 item、layout、strings、dimens 新增、页面、VM、Repository）
- 在 `settings.gradle.kts` 里新增业务模块并接入 `:app`
- 在 catalog 里新增**业务用**的依赖条目

不允许的：

- 改 `:base` / `:core` 的公共签名或实现细节
- 把业务类、业务资源放进 `:base`
- 打开 `dataBinding` / KSP
- 降级第 6 节里已修复的写法
- 用 `@Suppress` 掩盖新增的 lint 告警

---

## 10. base 文件索引（责任边界）

| 文件 | 职责 |
|---|---|
| `base/src/main/java/com/poria/base/BaseApp.kt` | Application 基类、全局 context、初始化 |
| `base/src/main/java/com/poria/base/ui/BaseActivity.kt` | Activity 基类、edge-to-edge、生命周期钩子 |
| `base/src/main/java/com/poria/base/ui/BaseFragment.kt` | Fragment 基类、viewLifecycleOwner 语义 |
| `base/src/main/java/com/poria/base/ui/BaseDialog.kt` | DialogFragment 基类、窗口参数 |
| `base/src/main/java/com/poria/base/store/DSManager.kt` | DataStore 键值存储 |
| `base/src/main/java/com/poria/base/util/ThreadPool.kt` | 后台线程池 + 协程调度器 |
| `base/src/main/java/com/poria/base/util/StrictModeConfig.kt` | debug 严格模式 |
| `base/src/main/java/com/poria/base/ext/ContextExt.kt` | dp/sp 换算 |
| `base/src/main/java/com/poria/base/ext/ViewExt.kt` | 点击动画 / 防抖 |
| `base/src/main/res/values/themes.xml` | `Theme.Base` |
| `base/src/main/res/values/dimens.xml` | 间距/圆角/字号/抬升 |
| `base/src/main/res/drawable/bg_corner_*.xml` | 圆角背景 |
| `base/consumer-rules.pro` | 对消费方的混淆规则 |
| `core/src/main/kotlin/com/poria/core/TimeExt.kt` | 时间戳 ⇄ `DateFormat`、星期 |
| `core/src/main/kotlin/com/poria/core/ColorExt.kt` | HSL → ARGB、稳定色生成 |
| `core/src/main/kotlin/com/poria/core/HashExt.kt` | MurmurHash3 finalizer |
| `core/src/test/kotlin/com/poria/core/*.kt` | 17 个 JVM 单测 |

---

## 11. 复制即用的模板

### 11.1 Application

```kotlin
package com.ppporia.nsdgames

import com.poria.base.BaseApp

class App : BaseApp()
```

### 11.2 页面 + ViewModel

```kotlin
// HomeViewModel.kt
class HomeViewModel(
    private val repo: HomeRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onScoreChanged(score: Int) {
        viewModelScope.launch {
            DSManager.setInt(Keys.HIGHEST_SCORE, score)
            _events.send(HomeEvent.SaveSucceeded)
        }
    }
}

// HomeActivity.kt
class HomeActivity : BaseActivity<ActivityHomeBinding>() {

    private val vm: HomeViewModel by viewModels()

    override fun onCreateBinding() = ActivityHomeBinding.inflate(layoutInflater)

    override fun initView() {
        binding.btnSave.setOnClickListenerWithClickAnimation(debounceMillis = 500) {
            vm.onScoreChanged(120)
        }
    }

    override suspend fun loadData() {
        // 每次回到前台都会执行：适合刷新排名/配置
    }

    override suspend fun bindUi() {
        launch { vm.state.collect { render(it) } }
        launch { vm.events.collect { handle(it) } }
    }

    private fun render(state: HomeState) {
        binding.tvScore.text = state.score.toString()
    }

    private fun handle(event: HomeEvent) {
        // Toast / 弹窗 / 跳转
    }
}
```

### 11.3 Fragment

```kotlin
class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    private val vm: HomeViewModel by activityViewModels()   // 与 Activity 共享

    override fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)

    override suspend fun bindUi() {
        vm.state.collect { state -> binding.tvTitle.text = state.title }
    }
}
```

### 11.4 Dialog

```kotlin
class ConfirmDialog : BaseDialog<DialogConfirmBinding>() {

    override fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        DialogConfirmBinding.inflate(inflater, container, false)

    override fun initView() {
        binding.root.setBackgroundResource(R.drawable.bg_corner_top_24)
    }

    override fun initListener() {
        binding.btnCancel.setOnDebouncedClickListener { dismiss() }
    }

    // 需要居中、固定宽高时重写：
    // override fun initWindowInsets(window: Window, ...) { super.initWindowInsets(window, Gravity.CENTER, ...) }
}
```

### 11.5 列表（上层自己写）

```kotlin
class ScoreAdapter : ListAdapter<Score, ScoreAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemScoreBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.tvName.text = getItem(position).name
    }

    class Holder(val binding: ItemScoreBinding) : RecyclerView.ViewHolder(binding.root)

    object Diff : DiffUtil.ItemCallback<Score>() {
        override fun areItemsTheSame(a: Score, b: Score) = a.id == b.id
        override fun areContentsTheSame(a: Score, b: Score) = a == b
    }
}
```

需要在 `:app/build.gradle.kts` 加 `implementation(libs.androidx.recyclerview)`，并先在 `gradle/libs.versions.toml` 里加对应条目。
