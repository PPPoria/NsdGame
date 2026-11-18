package com.poria.net.proto.session

import kotlinx.serialization.Serializable

/**
 * `ERROR` 载荷（双向，协议级错误，随后断开）。**FROZEN**（归属：传输与消息协议；签名审批：架构师）。
 *
 * 与 [Reject] 的分工：
 * - [Reject] 是**业务拒绝**（版本/满员/口令/黑名单），有固定的 `RejectCode` 枚举；
 * - [ProtocolError] 是**协议层错误**（"我没看懂你说的话"），码值开放、只用于日志与排障。
 *
 * ## 为什么这里的 [code] 可以是自由字符串（ADR-020 的例外说明）
 *
 * ADR-020 的规则是"**驱动 UI 表现的字段**必须是枚举"，不是"线缆不许有字符串"。
 * 本消息的 [code] / [message] 不驱动任何 UI 分支（客端只会断开并进日志），所以开放字符串是
 * 正当用法。反过来，若把"线缆不许有 String"当成规则，就会把这条一起误杀。
 *
 * 将来若真的需要按错误码分支（例如自动重连策略），那才需要改成枚举并升版本。
 *
 * 帧层错误（magic/长度/JSON）**不会**变成这条消息：那些在 `TcpTransport` 里直接以
 * `CloseReason.Protocol` 断开（对面已经不可信了，再回消息是浪费时间）。
 */
@Serializable
data class ProtocolError(

    /** 错误码（开放的字符串，例如 "UNEXPECTED_MESSAGE"、"SEQ_GAP_UNRECOVERABLE"）。`diagnostic-only`。 */
    val code: String,

    /** 可读描述（日志/排障用，允许为空）。`diagnostic-only`。 */
    val message: String? = null,
)
