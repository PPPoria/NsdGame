package com.poria.net.proto.session

import com.poria.net.proto.room.RoomDescriptor
import kotlinx.serialization.Serializable

/**
 * `WELCOME` 载荷（H→C 握手应答，紧跟 [Hello]）。**FROZEN**（归属：传输与消息协议；签名审批：架构师）。
 *
 * 字段权威定义见 `net-proto/PROTOCOL.md` §2.1。
 *
 * `room` 是**加入前**的房间快照，且标注为 `not-authoritative`（ADR-018）：
 * 它只用于两件事——**加入前的界面展示**（"正在加入 张三的房间 3/6"）与**版本预检**
 * （`protoVersion` / `gameId` / `gameVersion` 不兼容就别浪费一轮口令）。
 *
 * **权威状态永远是 `SNAPSHOT`**：名册、阶段、人数一律以随后的 `SNAPSHOT` 为准。
 * [RoomDescriptor.addresses] 在这里没有意义（对方已经连上来了），用
 * [RoomDescriptor.withoutAddresses] 去掉，见 [Welcome.of]。
 *
 * 不要为此新建一个近乎重复的 `RoomMeta` 类型（ADR-015）。
 */
@Serializable
data class Welcome(

    /** 房间元信息快照（加入**前**；权威值以随后的 [JoinOk.room] 为准）。 */
    val room: RoomDescriptor,

    /** 本次连接的会话 id（房主进程内唯一）。用于日志关联，**不是**重连凭证（那是 [JoinOk.sessionToken]）。 */
    val sessionId: String,
) {

    companion object {

        /** 按协议约定构造：自动去掉地址候选。 */
        fun of(descriptor: RoomDescriptor, sessionId: String): Welcome =
            Welcome(room = descriptor.withoutAddresses(), sessionId = sessionId)
    }
}
