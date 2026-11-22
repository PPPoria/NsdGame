package com.poria.netnsd.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** 帧协议版本。改动信封字段才动它；两边版本不一致时链路直接把人请出去。 */
const val LINK_VERSION = 1

/** 链路保留的频道名：报到 / 心跳 / 告别都在这儿，业务频道不许占用。 */
internal const val LINK_NS = "_link"

/**
 * 一条消息 = 一行 `FRAME|<json>`。
 *
 * **TcpLink 只认 [ns]**：按频道找到注册的 Handler，把 [p] 原样丢给它，不解析内容。
 * 帧里**没有**"这是命令还是推送"的字段：方向由"从哪条连接收到"决定——
 * 服务端那条连接上收到的是命令（[TcpHandler.onCommand]），客户端那条连接上收到的是推送
 * （[TcpHandler.onEvent]）。所以链路真的只是个路由器。
 *
 * ## 中转（星型点对点）
 * 带上 [to] 就是"请房主转给某个人"：
 * ```
 * 客人发：  FRAME|{"v":1,"ns":"chat","to":"guest-2","p":{...}}
 * 房主转：  FRAME|{"v":1,"ns":"chat","from":"guest-1","p":{...}}     ← from 由房主盖章
 * guest-2 收到：onEvent(payload, from = "guest-1")
 * ```
 * 帧里的 [from] **客户端发来的一律不信**：房主转发时会用自己的记录重新填。
 */
@Serializable
data class Frame(
    val v: Int = LINK_VERSION,
    /** 频道名：`lobby` / `blob` / `azul`…（`_link` 是链路保留的，Handler 不能注册）。 */
    val ns: String,
    /** 收件人（中转用）；`null` = 这条帧是发给房主/服务端本身的。 */
    val to: String? = null,
    /**
     * 谁发的：房主**直投**和中转都会填；房主广播出去的快照这类"服务端推的"是 `null`。
     * 客户端发来的这一位会被忽略（房主转发时用自己记下的发送者覆盖）。
     */
    val from: String? = null,
    /** 该频道的负载；链路不看、只转交。没有负载的帧（心跳、清空之类）就是 null。 */
    val p: JsonElement? = null,
)

/** `_link` 频道自己的小协议。 */
@Serializable
internal sealed interface LinkFrame {

    /**
     * 连上就报到。**不报身份**：id 由房主分配，见 [Welcome]。
     *
     * [hostClaim] 非空 = "我要求当房主"。它不是客户端随便填的：房主启动时会现场生成一份凭据，
     * 只有**自己那条回环连接**把手上的凭据带回来才算数（凭据不过网到别人手里）。
     */
    @Serializable
    @SerialName("hello")
    data class Hello(val hostClaim: String? = null) : LinkFrame

    /** 房主分给你的身份：从此这条链路上你叫 [playerId]。 */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val playerId: String) : LinkFrame

    @Serializable
    @SerialName("ping")
    data object Ping : LinkFrame

    @Serializable
    @SerialName("pong")
    data object Pong : LinkFrame

    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String) : LinkFrame
}

/**
 * 帧的编解码。Handler 要构造负载时用 [json]（和链路同一套序列化配置）。
 *
 * `ignoreUnknownKeys`：对端帧里多几个字段也照样解；
 * `explicitNulls = false`：没有负载的帧不会写一个 `"p":null` 出来。
 */
object LinkCodec {

    private const val PREFIX = "FRAME|"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(frame: Frame): String = PREFIX + json.encodeToString(frame)

    /** 解析失败返回 `null`：坏包不该让谁的进程崩。 */
    fun decode(line: String): Frame? {
        if (!line.startsWith(PREFIX)) return null
        return runCatching { json.decodeFromString<Frame>(line.substring(PREFIX.length)) }.getOrNull()
    }

    internal fun encode(msg: LinkFrame): JsonElement = json.encodeToJsonElement(msg)

    internal fun decodeLinkMessage(payload: JsonElement?): LinkFrame? {
        if (payload == null) return null
        return runCatching { json.decodeFromJsonElement<LinkFrame>(payload) }.getOrNull()
    }
}
