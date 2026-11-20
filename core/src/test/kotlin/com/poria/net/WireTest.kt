package com.poria.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.ProtocolException

/**
 * 行协议单测。纯内存，不碰 socket，`./gradlew :core:test` 秒过。
 */
class WireTest {

    private fun encode(vararg lines: String): ByteArray =
        ByteArrayOutputStream().also { out -> lines.forEach { Wire.writeLine(out, it) } }.toByteArray()

    private fun decode(bytes: ByteArray): List<String?> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { Wire.readLine(input) }.toList()
    }

    @Test
    fun `写得出去就读得回来`() {
        assertEquals(listOf("hello", "世界", ""), decode(encode("hello", "世界", "")))
    }

    @Test
    fun `UTF-8 多字节和 emoji 不会串码`() {
        val line = "UNO 出牌 ✅ 🎴 牌桌"
        assertEquals(line, decode(encode(line)).single())
    }

    @Test
    fun `行尾兼容 CRLF`() {
        val bytes = "a\r\nb\r\n".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("a", "b"), decode(bytes))
    }

    @Test
    fun `流结束返回 null；半条消息算异常`() {
        assertNull(Wire.readLine(ByteArrayInputStream(ByteArray(0))))
        assertThrows(EOFException::class.java) {
            Wire.readLine(ByteArrayInputStream("没换行就断了".toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `刚好到上限能过，超一字节就按协议错误断开`() {
        val exact = "a".repeat(Wire.MAX_LINE_BYTES)
        assertEquals(exact, decode(encode(exact)).single())

        val tooLong = ByteArray(Wire.MAX_LINE_BYTES + 1) { 'a'.code.toByte() }
        assertThrows(ProtocolException::class.java) {
            Wire.readLine(ByteArrayInputStream(tooLong))
        }
    }

    @Test
    fun `消息里不许带换行`() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { Wire.writeLine(out, "a\nb") }
        assertThrows(IllegalArgumentException::class.java) { Wire.writeLine(out, "a\rb") }
        assertThrows(IllegalArgumentException::class.java) { Wire.writeLine(out, "a".repeat(Wire.MAX_LINE_BYTES + 1)) }
        // 上面三次都必须一条字节都没写出去
        assertEquals(0, out.size())
    }
}
