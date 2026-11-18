# NsdGames

Android 游戏项目骨架。当前只有基础模块，玩法模块待建。

> **给实现上层的 AI / 开发者**：请先读 [`docs/BASE_MODULE_GUIDE.md`](docs/BASE_MODULE_GUIDE.md)。
> 它是 `:core` + `:base` 的唯一权威契约（API 签名、生命周期时序、资源约定、禁止项、变更申请流程）。
> `:base` / `:core` 由专人维护，上层不要直接改。

## 模块划分

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | 纯 Kotlin（`java-library`） | 与平台无关的工具：时间换算、HSL 颜色、哈希。零 Android 依赖，`./gradlew :core:test` 秒级跑完 |
| `:base` | Android Library | UI 基类（Activity/Fragment/Dialog）、View/Context 扩展、键值存储、线程池、StrictMode |
| `:app` | Android Application | 壳工程：Manifest、主题、启动页，把上面两个模块组装起来 |

依赖方向：`:app` → `:base` → `:core`。不允许反向依赖。

## 基础设施用法

### 1. Application

```kotlin
class App : BaseApp()
```

并在 `AndroidManifest.xml` 的 `<application android:name=".App">` 注册。
继承 `BaseApp` 会自动完成 `DSManager` 初始化和 debug 构建下的 StrictMode。

### 2. BaseActivity / BaseFragment / BaseDialog

```kotlin
class HomeActivity : BaseActivity<ActivityHomeBinding>() {
    override fun onCreateBinding() = ActivityHomeBinding.inflate(layoutInflater)
    override fun initView() { /* 同步、一次 */ }
    override fun initListener() { /* 同步、一次 */ }
    override suspend fun loadData() { /* 每次回到前台执行 */ }
    override suspend fun bindUi() { /* STARTED 期间订阅，退后台自动取消 */ }
}
```

```kotlin
class HomeFragment : BaseFragment<FragmentHomeBinding>() {
    override fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentHomeBinding.inflate(inflater, container, false)
}
```

```kotlin
class ConfirmDialog : BaseDialog<DialogConfirmBinding>() {
    override fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        DialogConfirmBinding.inflate(inflater, container, false)
}
```

生命周期钩子语义（三者一致）：

- `initView` / `initListener`：`onCreateView`/`onViewCreated` 里**同步**执行一次，绝不能放耗时操作。
- `loadData` / `bindUi`：包在 `repeatOnLifecycle(STARTED)` 里，回到前台重新执行、退后台自动取消，不会叠加 collector。
- `binding` 在 `onDestroy` / `onDestroyView` 里被释放，访问时机不对会立刻抛出可读的异常，而不是静默持有旧 View。

系统栏：

- `BaseActivity` 自动 `enableEdgeToEdge()`，并把系统栏 insets **叠加**到根布局原有 padding 上（不会清零你写的 padding）。
- 想换个 View 承载内边距，重写 `insetTarget`。
- 全屏游戏画面：`setImmersiveLayout(true)`；状态栏图标深浅：`setLightStatusBar()` / `setLightNavigationBar()`。
- 需要刘海/输入法处理时重写 `onWindowInsetsChanged(insets)`。

### 3. DSManager（键值存储）

```kotlin
DSManager.setInt("highest_score", 120)
DSManager.getInt("highest_score").collect { score -> /* ... */ }
```

- 所有 `getXxx` 都是 `Flow`，已做 `distinctUntilChanged`，无关 key 变化不会触发下游刷新。
- `Double` 用 long 位模式存储，精度无损（但别把同一个 key 既当 Long 又当 Double）。
- 需要删除时用 `remove(key)` / `clear()`。
- 高频写入（每帧级别）的数据不要放这里，DataStore 每次 edit 都会重写整个文件。

### 4. ThreadPool（后台线程）

```kotlin
withContext(ThreadPool.dispatcher) { /* 后台任务 */ }
```

有界队列（64）+ 2~4 线程 + 命名线程，抓 Systrace 时一眼能认出是哪个池。

### 5. 扩展函数

```kotlin
button.setOnClickListenerWithClickAnimation(debounceMillis = 500) { /* 带按压动画 + 防抖 */ }
button.setOnDebouncedClickListener { /* 只防抖 */ }
val px = context.dp2px(12f)
```

## 资源约定

- 尺寸用 `@dimen/space_*` / `@dimen/corner_*` / `@dimen/text_*` / `@dimen/elevation_*`，不要写魔法数字。
- 圆角背景用 `@drawable/bg_corner_*`，底色是 `?attr/colorSurface`，暗色模式自动适配。**不要**在 drawable 里写死 `#FFFFFF`。
- base 只提供主题基座 `Theme.Base`，app 里 `Theme.NsdGames` 继承它。改主题只改 app。

## 版本管理约定

- 所有依赖坐标和版本只在 `gradle/libs.versions.toml` 里，模块里**不允许**出现带版本号的依赖。
- 插件统一在根 `build.gradle.kts` 用 `apply false` 声明版本，模块里用 `alias(libs.plugins.xxx)` 应用。
- 库模块里出现在 public API 上的类型用 `api(...)`，纯实现细节用 `implementation(...)`。

## 常用命令

```bash
./gradlew :core:test          # 纯逻辑单测（最快）
./gradlew :app:assembleDebug  # 打包 debug
./gradlew :base:lintDebug     # 静态检查
```

## 待办

- [ ] 玩法模块（棋盘/存档/音效/渲染循环）
- [ ] 依赖注入（Hilt 或手写 ServiceLocator）
- [ ] 日志（Timber）
- [ ] 页面路由
- [ ] 平板/横屏尺寸（`values-sw600dp`、`values-land`）
