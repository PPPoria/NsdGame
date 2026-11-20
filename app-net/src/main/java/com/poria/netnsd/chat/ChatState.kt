package com.poria.netnsd.chat

/** 页面要渲染的状态。整体不可变，靠 [ChatSession.state] 一个个版本推给 UI。 */
data class ChatState(
    /** 是不是我在开房（决定标题上显不显示"房主"）。 */
    val host: Boolean = false,
    val phase: ChatPhase = ChatPhase.CONNECTING,
    val roomName: String = "",
    /** 服务端分给我的座位；0 = 还没进房。 */
    val mySeat: Int = 0,
    /** 花名册（按座位排序），只由服务端的 JOINED / LEFT 维护。 */
    val members: List<ChatMember> = emptyList(),
    /** 聊天记录，只由服务端的 LOG 追加（客户端不做本地回显，顺序只有一份真相）。 */
    val messages: List<ChatMessage> = emptyList(),
    /** 给用户看的一句提示：搜索/连接失败、被拒绝、断开原因。 */
    val note: String? = null,
) {
    val online: Boolean get() = phase == ChatPhase.ONLINE
}

enum class ChatPhase {
    /** 正在建房间 / 正在连别人。 */
    CONNECTING,

    /** 收到 WELCOME，可以说话了。 */
    ONLINE,

    /** 连接断了或被拒绝，页面该提示/退出。 */
    CLOSED,
}

data class ChatMember(val seat: Int, val name: String)

data class ChatMessage(val sender: String, val text: String, val mine: Boolean)
