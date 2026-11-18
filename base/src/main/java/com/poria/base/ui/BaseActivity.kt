package com.poria.base.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.launch

/**
 * Activity 基类：负责 edge-to-edge 与系统栏内边距，子类只管 View 和数据。
 *
 * 子类只需实现 [onCreateBinding]，例如：
 * ```
 * class HomeActivity : BaseActivity<ActivityHomeBinding>() {
 *     override fun onCreateBinding() = ActivityHomeBinding.inflate(layoutInflater)
 * }
 * ```
 *
 * 生命周期钩子约定：
 * - [initView] / [initListener]：只在 `onCreate` 里同步执行一次，**不要**在里面做耗时操作。
 * - [loadData] / [bindUi]：包在 `repeatOnLifecycle(STARTED)` 里，每次回到前台都会重新执行一次，
 *   退到后台会自动取消。这正是原来 `refreshDataInScope` + `onRestart` 想要的语义，但不会重复订阅。
 */
abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private var _binding: VB? = null

    /**
     * 视图绑定。只在 `onCreate` 之后、`onDestroy` 之前有效。
     * 用「取值时校验」而不是 `lateinit`，访问时机错了会立刻给出可读的报错。
     */
    protected val binding: VB
        get() = checkNotNull(_binding) {
            "binding 只能在 onCreate() 之后、onDestroy() 之前访问"
        }

    /** 系统栏四条边的内边距，下标顺序 `[left, top, right, bottom]`；insets 还没分发时全是 0。 */
    protected val systemBarPadding = IntArray(4)

    /** 系统栏内边距施加到哪个 View 上，默认是根布局。想放到某个子容器时重写它。 */
    protected open val insetTarget: View
        get() = binding.root

    private var immersive = false
    private val basePadding = IntArray(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        _binding = onCreateBinding().also { setContentView(it.root) }
        initEdgeToEdge()
        initView()
        initListener()
        observeLifecycleData()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 及时放手，避免配置变更期间还挂着旧 View 树
        _binding = null
    }

    /** 创建 ViewBinding，通常就是 `XxxBinding.inflate(layoutInflater)`。 */
    abstract fun onCreateBinding(): VB

    open fun initView() {}

    open fun initListener() {}

    /** 一次性/每次回前台的数据加载。运行在协程里，可以直接调用 suspend 函数。 */
    open suspend fun loadData() {}

    /**
     * 数据订阅。运行在 `repeatOnLifecycle(STARTED)` 里，可以直接 `viewModel.flow.collect { }`；
     * 退到后台会被取消，回到前台重新订阅，不会叠加 collector。
     */
    open suspend fun bindUi() {}

    /** insets 变化回调，需要额外处理刘海/键盘时可以重写。 */
    protected open fun onWindowInsetsChanged(insets: WindowInsetsCompat) {}

    private fun initEdgeToEdge() {
        val target = insetTarget
        basePadding[0] = target.paddingLeft
        basePadding[1] = target.paddingTop
        basePadding[2] = target.paddingRight
        basePadding[3] = target.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(target) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemBarPadding[0] = bars.left
            systemBarPadding[1] = bars.top
            systemBarPadding[2] = bars.right
            systemBarPadding[3] = bars.bottom
            applySystemBarPadding()
            onWindowInsetsChanged(insets)
            insets
        }
    }

    /**
     * 把系统栏内边距叠加到布局原有的 padding 上（而不是粗暴地把 padding 清零），
     * 这样布局自己声明的 padding 不会被吃掉。
     */
    private fun applySystemBarPadding() {
        val target = insetTarget
        if (immersive) {
            target.setPadding(basePadding[0], basePadding[1], basePadding[2], basePadding[3])
        } else {
            target.setPadding(
                basePadding[0] + systemBarPadding[0],
                basePadding[1] + systemBarPadding[1],
                basePadding[2] + systemBarPadding[2],
                basePadding[3] + systemBarPadding[3]
            )
        }
    }

    private fun observeLifecycleData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
                bindUi()
            }
        }
    }

    /** 状态栏图标用浅色（深色背景）还是深色（浅色背景）。 */
    protected fun setLightStatusBar(isLight: Boolean = true) {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = isLight
    }

    /** 导航栏图标同理。 */
    protected fun setLightNavigationBar(isLight: Boolean = true) {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = isLight
    }

    /**
     * 沉浸式（隐藏系统栏）。游戏画面铺满全屏时打开。
     * 与 [initEdgeToEdge] 的 padding 逻辑联动，随时可以来回切换。
     */
    protected fun setImmersiveLayout(isImmersive: Boolean = true) {
        immersive = isImmersive
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isImmersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        applySystemBarPadding()
    }
}
