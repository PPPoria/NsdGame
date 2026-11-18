package com.poria.net.core.transport

import com.poria.net.proto.frame.Message
import kotlinx.coroutines.flow.Flow

/**
 * 传输层契约。签名 FROZEN（ARCHITECTURE.md §2.3 / §12.1）。
 *
 * 归属：传输与消息协议；**签名审批：架构师**。
 *
 * 核心决策（ADR-001）：**契约上移到"完整一条消息"**，半包责任留在 Transport 内。
 * 否则上下两层都可能坏、两层都要测。
 *
 * `Message` 见 [com.poria.net.proto.frame.Message]：一整帧（9 字节头 + JSON 载荷）的字节。
 * 本层**不解析 JSON**，只负责"这段字节完整送达"与"帧非法就断开"。
 */

/** 连接目标抽象。用 sealed interface 而不是 `host:port` 字符串，为 BLE / Wi-Fi Direct 预留。 */
sealed interface Endpoint {
    data class Tcp(val host: String, val port: Int) : Endpoint
}

/** 传输实现。消费者：会话层（`:net-core/session`）、手动 IP 加入路径。 */
interface Transport {

    /** "tcp" / "fake"。用于日志与降级策略，不允许有业务含义。 */
    val scheme: String

    /**
     * 开始监听。
     *
     * @param port 传 0 表示**让系统分配**（ADR-008 必查项：避开固定端口被占 / 被防火墙拦的整类问题）。
     *             实际端口见 [Listener.localPort]，NSD 广播必须等它返回之后再 register。
     */
    suspend fun listen(port: Int = 0): Listener

    /**
     * 建立一条连接。
     *
     * @param timeoutMs 连接超时（毫秒）。多候选地址场景下由调用方逐个试、每个给 ~1.5s。
     */
    suspend fun connect(endpoint: Endpoint, timeoutMs: Long): Connection
}

/** 监听器。消费者：房主侧会话层 + 服务发现（拿 [localPort] 去广播）。 */
interface Listener {

    /** 实际监听端口；若 [Transport.listen] 传入 0，这里是系统分配的真实端口。 */
    val localPort: Int

    /** 入站连接流。**一次性**：每个连接只发一次。 */
    val incoming: Flow<Connection>

    /** 关闭监听并断开所有已接受的连接。 */
    suspend fun close()
}

/** 一条已分帧的消息通道。**无游戏语义**。 */
interface Connection {

    /** 仅日志用途（"192.168.1.7:41234"），不允许参与任何判断。 */
    val peerLabel: String

    /** 入站完整消息（已按帧切好，永不丢半包）。 */
    val incoming: Flow<Message>

    /** 关闭原因。单次发射：连接关闭后收集会立即拿到原因，之前则挂起。 */
    val closed: Flow<CloseReason>

    /**
     * 发送一条完整消息。**挂起直到写完**（背压），不是"丢进队列就返回"。
     *
     * @throws java.io.IOException 连接已关闭、对端不消费导致写超时、或写完前出了 IO 错误
     */
    suspend fun send(msg: Message)

    /** 主动关闭（幂等）。 */
    suspend fun close(reason: CloseReason)
}

/**
 * 连接关闭原因。传输角色只**产出**它；重连策略属于会话层（本层不做重连决策）。
 */
sealed interface CloseReason {

    /** 流在帧边界上正常结束（对端 socket 正常关闭）。 */
    data object Eof : CloseReason

    /** 对端异常断开（RST / 连接被重置 / 管道破裂）。 */
    data object RemoteClosed : CloseReason

    /** 本地 IO 错误（含写超时）。 */
    data class Io(val cause: Throwable) : CloseReason

    /** 帧非法或超长：对面不是本协议，或对面坏了。 */
    data class Protocol(val message: String) : CloseReason

    /** 本端主动关闭。 */
    data object LocalClose : CloseReason
}
