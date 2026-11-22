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
 * 页面之间不传递链路，房间页/对局页各自的 ViewModel 都从它拿（`App.session.connection`），
 * 所以"房间 → 对局"这种换页不需要把 socket 交给谁，它本来就没跟着页面走。
 */
class App : BaseApp() {

    /** 网络会话；构造只存 applicationContext，真正的 socket 在 `host`/`join` 里建。 */
    val session: Session by lazy { Session(this) }
}
