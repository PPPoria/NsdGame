package com.ppporia.nsdgames

import com.poria.base.BaseApp
import com.poria.netnsd.Session

/**
 * 宿主 Application。
 *
 * 继承 [BaseApp] 之后自动获得：DSManager 初始化、debug 下的 StrictMode。
 * 别忘了在 AndroidManifest 的 `<application android:name=".App">` 里注册。
 *
 * 这里只多放一样东西：[session]——**进程里唯一的那条连接**。
 * 页面之间不传递链路，房间页/对局页各自的 ViewModel 都从 `App.session.connection` 拿，
 * 所以"房间 → 对局"这种换页不需要把 socket 交给谁，它本来就没跟着页面走。
 */
class App : BaseApp() {

    companion object {

        /**
         * 网络会话，整个进程一个。
         *
         * `Session` 的构造器是 `private`、[Session.getInstance] 是唯一入口，所以"进程里只有一条
         * 网络连接"是编译器保证的，不是约定。这里再包一层只是为了调用点好写：`App.session`。
         *
         * 第一次访问发生在 ViewModel/Activity 里（主线程），而 `Session` 构造只存
         * `applicationContext`，真正的 socket 在 `host/join` 里由 IO 线程建，不碰 StrictMode。
         */
        val session: Session get() = Session.getInstance(context)
    }
}
