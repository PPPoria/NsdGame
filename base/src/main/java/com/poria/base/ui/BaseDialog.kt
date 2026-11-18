package com.poria.base.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.launch

/**
 * DialogFragment 基类，生命周期钩子与 [BaseActivity] 保持一致。
 *
 * 注意 [initView] / [initListener] 是**同步**执行的（在 onViewCreated 里），
 * 不要把它们塞进 suspend 的 [loadData] 后面 —— 否则数据一挂起，界面就是一片空白。
 */
abstract class BaseDialog<VB : ViewBinding> : DialogFragment() {

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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loadData()
                bindUi()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { initWindowInsets(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    abstract fun onCreateBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    open fun initView() {}

    open fun initListener() {}

    open suspend fun loadData() {}

    open suspend fun bindUi() {}

    /**
     * 窗口参数：默认底部弹窗 + 宽度撑满 + 高度自适应 + 透明背景。
     * 想做居中、固定宽高、圆角遮罩时重写它。
     */
    protected open fun initWindowInsets(
        window: Window,
        gravity: Int = Gravity.BOTTOM,
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT
    ) {
        val params = window.attributes
        params.gravity = gravity
        params.width = width
        params.height = height
        window.attributes = params
        window.setGravity(gravity)
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    }
}
