package com.poria.net.proto.session

import kotlinx.serialization.Serializable

/**
 * `JOIN_REQ` 载荷（C→H，收到 [Welcome] 之后发）。**FROZEN**（归属：传输与消息协议；签名审批：架构师）。
 *
 * 与 [Hello] 分开的理由：`HELLO` 回答"你是谁、什么版本"，`JOIN_REQ` 回答"我要用这个口令坐进去"。
 * 合在一起会让"版本不匹配"与"口令错"共用一条失败路径，客端的重试策略就分不开了。
 *
 * [passcode] 是**明文**（D6，局域网场景刻意不做哈希）：房主明文比对，只比对不广播、不落日志。
 */
@Serializable
data class JoinReq(

    /** 房间口令（明文，D6）。房间未设口令时为 null。 */
    val passcode: String? = null,

    /** 客端昵称（可改写 [Hello.nick]，以本字段为准）。 */
    val nick: String,
)
