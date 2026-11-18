package com.poria.base

import android.app.Application
import android.content.Context
import com.poria.base.store.DSManager
import com.poria.base.util.StrictModeConfig

/**
 * 宿主 Application 的基类。
 *
 * 用法：让 app 模块的 Application 继承它，并在 AndroidManifest 的 `<application android:name>` 里注册。
 * 继承它会自动完成两件事：初始化 [DSManager]、debug 构建下打开 StrictMode。
 *
 * 如果不想继承（比如宿主要用自己的 Application），可以在自己的 `onCreate` 里手动调用
 * `DSManager.init(this)`。
 */
open class BaseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        DSManager.init(this)
        if (BuildConfig.DEBUG) {
            StrictModeConfig.enable()
        }
    }

    companion object {
        private lateinit var instance: BaseApp

        /**
         * 全局 Application 实例。不要把它传给长生命周期的对象（虽然 Application 本身不会泄漏，
         * 但会绑定具体类型，不利于测试）。
         */
        val app: BaseApp
            get() = instance

        /** 需要 Context 时优先用它，避免持有 Activity。 */
        val context: Context
            get() = instance.applicationContext

        /** 是否已经初始化，方便在库代码里做兜底判断。 */
        val isInitialized: Boolean
            get() = ::instance.isInitialized
    }
}
