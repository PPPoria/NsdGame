package com.poria.netnsd.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议单测：编解码必须严格对称，坏包必须返回 null（不能抛、不能让谁的进程崩）。
 *
 * 这些类不碰 Android，所以在 `:app-net` 里也能用普通 JUnit 跑：`./gradlew :app-net:test`。
 */
class ChatProtocolTest {

    @Test
    fun `命令往返`() {
        assertEquals(ChatCommand.Join("小明"), ChatProtocol.decodeCommand(ChatProtocol.join("  小明  ")))
        assertEquals(ChatCommand.Say("你好"), ChatProtocol.decodeCommand(ChatProtocol.say("你好")))
    }

    @Test
    fun `事件往返`() {
        assertEquals(ChatEvent.Welcome(2, "小明的房间"), ChatProtocol.decodeEvent(ChatProtocol.welcome(2, "小明的房间")))
        assertEquals(ChatEvent.Joined(3, "小红"), ChatProtocol.decodeEvent(ChatProtocol.joined(3, "小红")))
        assertEquals(ChatEvent.Left(1), ChatProtocol.decodeEvent(ChatProtocol.left(1)))
        assertEquals(ChatEvent.Log(1, "开一局？"), ChatProtocol.decodeEvent(ChatProtocol.log(1, "开一局？")))
        assertEquals(ChatEvent.Error("房间满了"), ChatProtocol.decodeEvent(ChatProtocol.error("房间满了")))
    }

    @Test
    fun `文本里的竖线不会被切断`() {
        val tricky = "a|b||c"
        assertEquals(ChatCommand.Say(tricky), ChatProtocol.decodeCommand(ChatProtocol.say(tricky)))
        assertEquals(ChatEvent.Log(1, tricky), ChatProtocol.decodeEvent(ChatProtocol.log(1, tricky)))
        assertEquals(ChatEvent.Welcome(1, "房间|名字"), ChatProtocol.decodeEvent(ChatProtocol.welcome(1, "房间|名字")))
        assertEquals(ChatEvent.Joined(1, "昵|称"), ChatProtocol.decodeEvent(ChatProtocol.joined(1, "昵|称")))
    }

    @Test
    fun `换行会被抹成空格`() {
        // 行协议根本不允许换行，这里挡的是调用方手滑
        assertEquals(ChatCommand.Say("上 下"), ChatProtocol.decodeCommand(ChatProtocol.say("上\n下")))
        assertTrue(ChatProtocol.say("上\r下").indexOf('\n') < 0)
    }

    @Test
    fun `超长内容会被截断`() {
        val long = "字".repeat(ChatProtocol.MAX_TEXT + 50)
        val decoded = ChatProtocol.decodeCommand(ChatProtocol.say(long)) as ChatCommand.Say
        assertEquals(ChatProtocol.MAX_TEXT, decoded.text.length)

        val longName = "名".repeat(ChatProtocol.MAX_NAME + 5)
        val join = ChatProtocol.decodeCommand(ChatProtocol.join(longName)) as ChatCommand.Join
        assertEquals(ChatProtocol.MAX_NAME, join.name.length)
    }

    @Test
    fun `坏包返回 null 而不是抛异常`() {
        listOf("", "|x", "NOPE|x", "JOIN", "SAY", "unknown|x").forEach {
            assertNull("命令 `$it` 应该被忽略", ChatProtocol.decodeCommand(it))
        }
        listOf("", "|x", "WELCOME|abc|房间", "JOINED|x|名字", "LEFT|x", "LOG|x|内容", "NOPE|1|x").forEach {
            assertNull("事件 `$it` 应该被忽略", ChatProtocol.decodeEvent(it))
        }
    }
}
