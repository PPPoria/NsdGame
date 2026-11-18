package com.poria.base.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.launch

/**
 * Fragment 基类，生命周期钩子与 [BaseActivity] 保持一致。
 *
 * 关键点：binding 在 [onDestroyView] 里必须置空。
 * 用 `by lazy` 缓存 binding 会同时导致两件事：View 树泄漏，以及 Fragment 被重新 attach 时
 * 沿用已销毁的旧 root（`IllegalStateException: The specified child already has a parent`）。
 *
 * 用法：
 * ```
 * class HomeFragment : BaseFragment<FragmentHomeBinding>() {
 *     override fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?) =
 *         FragmentHomeBinding.inflate(inflater, container, false)
 * }
 * ```
 */
abstract class BaseFragment<VB : ViewBinding> : Fragment() {

    private var _binding: VB? = null

    /** 只在 `onCreateView` 之后、`onDestroyView` 之前有效。 */
    protected val binding: VB
        get() = checkNotNull(_binding) {
            "binding 只能在 onViewCreated() 到 onDestroyView() 之间访问"
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = onCreateBinding(inflater, container)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initListener()
        // 用 viewLifecycleOwner 而不是 Fragment 自身的 lifecycle，
        // 否则 View 已销毁但 Fragment 还活着时协程仍在跑，容易碰空 binding。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
                bindUi()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 创建 ViewBinding，通常是 `XxxBinding.inflate(inflater, container, false)`。 */
    abstract fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    open fun initView() {}

    open fun initListener() {}

    /** 数据加载，每次 View 可见（STARTED）时执行。 */
    open suspend fun loadData() {}

    /** 数据订阅，随 STARTED/STOPPED 自动订阅与取消。 */
    open suspend fun bindUi() {}
}
