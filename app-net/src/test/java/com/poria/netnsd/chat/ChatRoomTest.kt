package com.poria.netnsd.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间规则单测：假的 `send` 就是"某个客户端收到的消息列表"，于是不需要任何 socket。
 */
class ChatRoomTest {

    private val room = ChatRoom("测试房间")
    private val aliceLines = mutableListOf<String>()
    private val bobLines = mutableListOf<String>()

    private fun alice(name: String = "Alice") = room.join(name) { aliceLines += it; true }

    private fun bob(name: String = "Bob") = room.join(name) { bobLines += it; true }

    @Test
    fun `进房先收到欢迎和现有成员，其他人收到进房通知`() {
        assertEquals(ChatRoom.JoinResult.Joined(1), alice())
        assertEquals("第一条必须是 WELCOME", ChatProtocol.welcome(1, "测试房间"), aliceLines.first())
        assertEquals("自己也要收到自己的 JOINED", ChatProtocol.joined(1, "Alice"), aliceLines.last())
        assertEquals(2, aliceLines.size)

        assertEquals(ChatRoom.JoinResult.Joined(2), bob())

        assertEquals("新人的第一条也是 WELCOME", ChatProtocol.welcome(2, "测试房间"), bobLines.first())
        assertTrue("新人要拿到现有花名册", bobLines.contains(ChatProtocol.joined(1, "Alice")))
        assertEquals("老人要收到新人进房", ChatProtocol.joined(2, "Bob"), aliceLines.last())
    }

    @Test
    fun `说的话广播给所有人，包括发送者自己`() {
        alice()
        bob()
        aliceLines.clear()
        bobLines.clear()

        assertTrue(room.say(1, "开一局？"))

        assertEquals(listOf(ChatProtocol.log(1, "开一局？")), aliceLines)
        assertEquals(listOf(ChatProtocol.log(1, "开一局？")), bobLines)
    }

    @Test
    fun `内容里的竖线原样保留`() {
        alice()
        room.say(1, "a|b|c")
        assertTrue(aliceLines.last().endsWith("a|b|c"))
    }

    @Test
    fun `空消息和不在房间里的人发不了话`() {
        alice()
        aliceLines.clear()

        assertFalse("空内容不该发出去", room.say(1, "   "))
        assertFalse("不存在的座位不该发出去", room.say(99, "有人吗"))

        assertTrue("两条都没发出去", aliceLines.isEmpty())
    }

    @Test
    fun `昵称重复、空昵称都会被拒绝`() {
        assertEquals(ChatRoom.JoinResult.Joined(1), alice())
        assertEquals("重名要拒绝", ChatRoom.JoinResult.DuplicateName, bob("Alice"))
        assertEquals("空昵称要拒绝", ChatRoom.JoinResult.InvalidName, bob("   "))
        assertTrue("被拒绝的不占座位", bobLines.isEmpty())
    }

    @Test
    fun `满员后不能再进人`() {
        val small = ChatRoom("小房间", capacity = 2)
        assertEquals(ChatRoom.JoinResult.Joined(1), small.join("A") { true })
        assertEquals(ChatRoom.JoinResult.Joined(2), small.join("B") { true })
        assertEquals(ChatRoom.JoinResult.Full, small.join("C") { true })
        assertEquals(2, small.size)
    }

    @Test
    fun `离开会广播 LEFT，并释放座位名额`() {
        alice()
        bob()
        bobLines.clear()

        room.leave(1)

        assertEquals(listOf(ChatProtocol.left(1)), bobLines)
        assertEquals(listOf("Bob"), room.memberNames())
        assertEquals("座位号不复用，重进拿新号", ChatRoom.JoinResult.Joined(3), room.join("Alice") { true })

        bobLines.clear()
        room.leave(99) // 不认识的座位：什么都不该发生
        assertTrue(bobLines.isEmpty())
    }
}
