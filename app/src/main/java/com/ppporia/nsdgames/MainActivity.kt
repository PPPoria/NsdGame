package com.ppporia.nsdgames

import android.os.Bundle
import com.poria.base.ui.BaseActivity
import com.poria.base.ext.setOnClickListenerWithClickAnimation
import com.ppporia.nsdgames.databinding.ActivityMainBinding

/**
 * 空壳页面，用来验证 base 的基类可用。
 * edge-to-edge、系统栏内边距都由 [BaseActivity] 处理，这里不用再写 findViewById(R.id.main) 那套。
 */
class MainActivity : BaseActivity<ActivityMainBinding>() {

    override fun onCreateBinding(): ActivityMainBinding =
        ActivityMainBinding.inflate(layoutInflater)

    override fun initView() {
        binding.hello.text = getString(R.string.hello_placeholder)
    }

    override fun initListener() {
        // 带按压动画 + 500ms 防抖，连点不会重复触发
        binding.hello.setOnClickListenerWithClickAnimation(debounceMillis = 500) {
            binding.hello.text = getString(R.string.hello_clicked)
        }
    }

    override suspend fun loadData() {
        // 数据加载放这里（可以直接调用 suspend 函数），每次回到前台执行一次
    }
}
