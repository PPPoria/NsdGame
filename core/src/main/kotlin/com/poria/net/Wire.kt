package com.poria.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException

/**
 * 行协议：一条消息 = 一行 UTF-8 文本 + 一个 `'\n'`。
 *
 * 为什么是行协议而不是二进制帧：这个项目一局最多 6 人、消息量按"每秒几条"算，
 * 行协议能让人 `telnet host port` 上去手敲调试，代码也就几十行。
 * 消息里禁止出现 `'\n'` / `'\r'`，所以**不需要转义**；行尾兼容 Windows 的 `\r\n`。
 *
 * 所有函数都是阻塞的：线程由调用方负责（[Peer] 的读线程已经起好了，直接用）。
 */
object Wire {

    /** 单条消息的字节上限。超了按协议错误断开——防止对端一条消息把内存吃光。 */
    const val MAX_LINE_BYTES = 8 * 1024

    /**
     * 写一行并 flush。
     *
     * @throws IllegalArgumentException 消息含换行符或超过 [MAX_LINE_BYTES]（本机代码 bug，不是网络问题）
     * @throws IOException 连接已断
     */
    fun writeLine(out: OutputStream, line: String) {
        checkLine(line)
        out.write(line.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        // 小包不 flush 会卡在缓冲区里等到下一次发送，表现为"对面半天没收到"
        out.flush()
    }

    /**
     * 读一行。**空行是合法消息**，返回 `""`。
     *
     * @return 消息内容（不含行尾）；连接正常关闭（EOF）返回 `null`
     * @throws EOFException 连接在一条消息的中途断掉——这算异常，不算正常关闭
     * @throws ProtocolException 一行超过 [MAX_LINE_BYTES]
     * @throws IOException 其他网络错误
     */
    fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buffer.size() == 0) return null
                throw EOFException("连接在一条消息的中途断开（收到 ${buffer.size()} 字节）")
            }
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) {
                throw ProtocolException("一条消息超过 $MAX_LINE_BYTES 字节，断开")
            }
        }
    }

    /**
     * 本机参数自检：含换行、超长。
     *
     * 广播时先用它挡一次，免得第一台上限一样的 peer 抛出、后面几百个白跑。
     */
    internal fun checkLine(line: String) {
        require(line.indexOf('\n') < 0 && line.indexOf('\r') < 0) {
            "消息里不能有换行符（要发多行就一条一条发）：$line"
        }
        require(line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES) {
            "消息太长：${line.length} 个字符，上限 ${MAX_LINE_BYTES} 字节"
        }
    }
}
