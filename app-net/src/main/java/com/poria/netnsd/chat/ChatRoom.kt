package com.poria.netnsd.chat

/**
 * 房主侧的聊天室：谁在房间里、谁说了什么、这句话该发给谁。
 *
 * **不碰 socket**：每个成员只给一个 `send` 回调（会话层传 `peer::send` 进来），
 * 所以单元测试里用几个假的发送器就能把规则测干净。
 *
 * ⚠️ [join] / [say] / [leave] 在锁内直接调用 `send`：某一端写阻塞会拖慢整个房间。
 * 局域网聊天的量级可以接受（和 `Peer.send` 的阻塞写是同一个取舍），
 * 但别在 `send` 里做重活，也别指望它是异步的。
 */
class ChatRoom(
    roomName: String,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    /** 房间名（已清洗、已截断）。 */
    val roomName: String = roomName.replace('\r', ' ').replace('\n', ' ').trim().take(ChatProtocol.MAX_ROOM)

    sealed interface JoinResult {
        data class Joined(val seat: Int) : JoinResult

        /** 房间满了。 */
        data object Full : JoinResult

        /** 昵称已被占用。 */
        data object DuplicateName : JoinResult

        /** 昵称为空。 */
        data object InvalidName : JoinResult
    }

    private class Member(val seat: Int, val name: String, val send: (String) -> Boolean)

    private val lock = Any()
    private val members = LinkedHashMap<Int, Member>()
    private var nextSeat = 1

    /** 当前在房间里的人（按进房顺序）。 */
    fun memberNames(): List<String> = synchronized(lock) { members.values.map { it.name } }

    val size: Int get() = synchronized(lock) { members.size }

    /**
     * 进房。成功后：
     * 1. 给新人发 [ChatProtocol.welcome]；
     * 2. 把现有成员逐个 [ChatProtocol.joined] 告诉新人；
     * 3. 把新人 [ChatProtocol.joined] 广播给**所有人（含本人）**。
     *
     * 第 3 步带上本人，是为了让所有客户端只需要"照着 JOINED 建花名册"这一条规则。
     */
    fun join(name: String, send: (String) -> Boolean): JoinResult {
        val cleanName = name.replace('\r', ' ').replace('\n', ' ').trim().take(ChatProtocol.MAX_NAME)
        if (cleanName.isEmpty()) return JoinResult.InvalidName

        synchronized(lock) {
            if (members.size >= capacity) return JoinResult.Full
            if (members.values.any { it.name == cleanName }) return JoinResult.DuplicateName

            val member = Member(nextSeat++, cleanName, send)
            member.send(ChatProtocol.welcome(member.seat, roomName))
            members.values.forEach { member.send(ChatProtocol.joined(it.seat, it.name)) }

            members[member.seat] = member
            val line = ChatProtocol.joined(member.seat, member.name)
            members.values.forEach { it.send(line) }
            return JoinResult.Joined(member.seat)
        }
    }

    /**
     * 说一句话，广播给房间里的所有人（**包括发送者**：客户端不做本地回显，顺序才只有一份真相）。
     *
     * @return 是否真的发出去了（空消息、或者这人已经不在房间里，都返回 false）
     */
    fun say(seat: Int, text: String): Boolean {
        val cleanText = text.replace('\r', ' ').replace('\n', ' ').trim().take(ChatProtocol.MAX_TEXT)
        if (cleanText.isEmpty()) return false

        synchronized(lock) {
            if (!members.containsKey(seat)) return false
            val line = ChatProtocol.log(seat, cleanText)
            members.values.forEach { it.send(line) }
            return true
        }
    }

    /** 离开房间，并广播 [ChatProtocol.left]。不认识的座位号直接忽略。 */
    fun leave(seat: Int) {
        synchronized(lock) {
            if (members.remove(seat) == null) return
            val line = ChatProtocol.left(seat)
            members.values.forEach { it.send(line) }
        }
    }

    companion object {
        /** 一屋子聊天的上限。 */
        const val DEFAULT_CAPACITY = 8
    }
}
