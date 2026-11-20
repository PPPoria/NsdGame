package com.poria.netnsd.room

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * 二进制在 JSON 里怎么走：**base64 字符串**。
 *
 * 自己写一个 serializer 而不是到处手搓 `Base64.getEncoder()`，好处是模型里可以直接放
 * `ByteArray`，编解码只有一处；base64 的字符表 `A-Za-z0-9+/=` 在 JSON 里不需要再转义，
 * 代价是 **+33%**（4KB 二进制 → 5.4KB 的 base64，一行留得下）。
 */
private object Base64Bytes : KSerializer<ByteArray> {

    override val descriptor =
        PrimitiveSerialDescriptor("com.poria.netnsd.room.Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

/**
 * 服务端 → 客户端的一条图片分块。
 *
 * `total` 是总块数（客户端靠它知道什么时候拼完），`index` 从 0 开始；
 * 同一条连接保序，所以客户端收到就是顺序的，乱序一律当坏包。
 */
@Serializable
data class BlobChunkFrame(
    val id: String,
    val index: Int,
    val total: Int,
    @Serializable(with = Base64Bytes::class) val data: ByteArray,
)

/** 拉图/上传失败的一句话原因。 */
@Serializable
data class BlobErrorFrame(val id: String, val reason: String)

/**
 * 客户端 → 服务端的命令。客户端只能"请求"，能不能改由 [Room] 说了算。
 *
 * 线上就是一个 JSON 对象，`type` 字段区分是哪个命令：
 * ```
 * CMD|{"type":"join","playerId":"p1","name":"小明"}
 * CMD|{"type":"say","text":"开一局？"}
 * CMD|{"type":"start"}
 * ```
 *
 * 客户端**不做本地回显/推演**：发完命令什么都不改，等服务端把新的
 * [RoomSnapshot] 推回来才动界面。
 */
@Serializable
sealed interface RoomCommand {

    /** 进房报到。[playerId] 由客户端自己生成，服务端认可后就是这间房里的身份。 */
    @Serializable
    @SerialName("join")
    data class Join(val playerId: String, val name: String) : RoomCommand

    @Serializable
    @SerialName("say")
    data class Say(val text: String) : RoomCommand

    /** 我准备好了 / 取消准备。 */
    @Serializable
    @SerialName("ready")
    data class Ready(val ready: Boolean) : RoomCommand

    /** 自己挪到一个**空**座位（有人坐的座位只能房主调）。 */
    @Serializable
    @SerialName("seat")
    data class TakeSeat(val seat: Int) : RoomCommand

    /** 房主：交换两个座位（其中一个是空位就是"把人挪过去"）。 */
    @Serializable
    @SerialName("swap")
    data class SwapSeats(val a: Int, val b: Int) : RoomCommand

    /** 房主：改牌桌人数。 */
    @Serializable
    @SerialName("size")
    data class SetTableSize(val size: Int) : RoomCommand

    /** 房主：换游戏。 */
    @Serializable
    @SerialName("game")
    data class SelectGame(val gameId: String) : RoomCommand

    /** 房主：开关一个自定义玩法。 */
    @Serializable
    @SerialName("option")
    data class SetOption(val optionId: String, val on: Boolean) : RoomCommand

    /** 房主：请人离开。 */
    @Serializable
    @SerialName("kick")
    data class Kick(val playerId: String) : RoomCommand

    /** 房主：开始游戏（对局规则没写之前会被服务端挡下）。 */
    @Serializable
    @SerialName("start")
    data object Start : RoomCommand

    /**
     * 要一张图。房主会按 [RoomProtocol.BLOB_CHUNK_BYTES] 一块、用 `BLOB` 事件推回来。
     *
     * **点对点**：只有发起人要，不做广播——50 张图不会变成 50 × 人数 的流量。
     */
    @Serializable
    @SerialName("blobRequest")
    data class RequestBlob(val id: String) : RoomCommand

    /**
     * 上传一张图（自己的头像、自定义砖块纹理等），分块发，最后一块到了房主才认。
     *
     * [role] 说明这张图是干什么用的，现在只有 [RoomProtocol.ROLE_AVATAR]（收齐就自动成为我的头像）。
     */
    @Serializable
    @SerialName("blobPut")
    data class PutBlob(
        val id: String,
        val index: Int,
        val total: Int,
        @Serializable(with = Base64Bytes::class) val data: ByteArray,
        val role: String = RoomProtocol.ROLE_AVATAR,
    ) : RoomCommand
}

/** 服务端 → 客户端的事件。三类：权威快照、单条错误、请你离开。外加图片分块。 */
sealed interface RoomEvent {
    /** **唯一的数据通道**：房间每次变化，服务端把整份快照推给所有人（含发起者）。 */
    data class Snapshot(val snapshot: RoomSnapshot) : RoomEvent

    /** 刚才那条命令没生效，[reason] 直接给人看；房间状态没动。 */
    data class Error(val reason: String) : RoomEvent

    /** 服务端要关掉这条连接（满员、被踢、房间解散），[reason] 直接给人看。 */
    data class Bye(val reason: String) : RoomEvent

    /** 一张图的一块（[data] 已经是 base64 解好的原始字节）。拼完交给 [BlobStore]。 */
    data class Blob(val id: String, val index: Int, val total: Int, val data: ByteArray) : RoomEvent

    /** 拉图/上传被拒绝，[reason] 直接给人看。 */
    data class BlobError(val id: String, val reason: String) : RoomEvent
}

/**
 * 房间的行协议：`类型|负载`，一条消息一行（`:core` 的 `Wire` 保证没有换行）。
 *
 * **负载永远是 JSON**（kotlinx.serialization）：快照和命令都是 `@Serializable` 数据类，
 * 所以这里没有手写的字段拼接、也没有自己的转义表——昵称/房名/聊天内容里带 `|`、引号、
 * 反斜杠、换行都交给 JSON 处理。加字段时只改数据类，`ignoreUnknownKeys` 保证新旧版本
 * 至少不会互相炸掉。
 *
 * ```
 * 客户端 → 服务端
 *   CMD|<RoomCommand 的 JSON>       type = join / say / ready / seat / swap / size / game / option / kick / start
 *
 * 服务端 → 客户端
 *   SNAP|<RoomSnapshot 的 JSON>     唯一的数据通道（每次变化推给所有人）
 *   ERROR|<JSON 字符串>             刚才那条命令没生效
 *   BYE|<JSON 字符串>               这条连接要被关掉
 * ```
 *
 * **服务端只推快照**：旧版是 `JOINED` / `LEFT` / `LOG` 一串增量事件让客户端自己攒花名册，
 * 攒错一次就永远两边不一致；现在整份覆盖，客户端没有"我的花名册"这个概念，
 * 只有"最近一次收到的快照"。
 *
 * 单行有上限（`:core` 的 `Wire` 是 8KB），[snapshot] 超预算时从**最老的聊天**开始丢，
 * 人和设置一条不少（JSON 比手写格式多几十字节/条，所以聊天能留的条数会少一点，
 * 这是换可维护性的代价）。
 */
object RoomProtocol {

    /**
     * 快照版本。
     *
     * v1 是手写的 `键=值` 格式；**v2 起整条协议是 JSON**（`type` 字段区分消息/命令种类）。
     * 版本不一致就整份丢掉：宁可不动，也不渲染半份状态。
     */
    const val VERSION = 2

    const val MAX_NAME = 12
    const val MAX_TEXT = 300
    const val MAX_ROOM = 24
    const val MAX_ID = 16
    private const val MAX_REASON = 80

    /** 快照单行的目标字节数：比 `Wire.MAX_LINE_BYTES`（8KB）留出余量。 */
    const val MAX_SNAPSHOT_BYTES = 6_000

    // ── 图片（blob）的尺寸约束 ───────────────────────────────────────────────

    /**
     * 一块的原始字节数。
     *
     * 取 3KB 是因为 base64 后正好 4KB、加上 JSON 骨架约 4.2KB，**稳在 8KB 行上限以内**；
     * 同时单次阻塞写足够小，图片传输不会把聊天/快照卡住几百毫秒。
     */
    const val BLOB_CHUNK_BYTES = 3_072

    /** 单张图的上限（512px 的头像/纹理一般 20–60KB，4MB 已经很宽松了）。 */
    const val MAX_BLOB_BYTES = 4 * 1024 * 1024

    /** 单张图最多几块（防止对端用 `total` 把上传缓冲区撑爆）。 */
    const val MAX_BLOB_CHUNKS = MAX_BLOB_BYTES / BLOB_CHUNK_BYTES + 1

    /** 本机缓存最多留多少字节的图，超了按 LRU 淘汰。 */
    const val MAX_BLOB_STORE_BYTES = 64L * 1024 * 1024

    const val MAX_BLOB_ID = 64

    /** 上传时说明"这张图是我的头像"：房主收齐就把它写进我的快照。 */
    const val ROLE_AVATAR = "avatar"

    private const val CMD = "CMD"
    private const val SNAP = "SNAP"
    private const val ERROR = "ERROR"
    private const val BYE = "BYE"
    private const val BLOB = "BLOB"
    private const val BLOB_ERR = "BLOB_ERR"

    /**
     * `ignoreUnknownKeys`：对端多带了字段（对方版本更新）也照样解，不会把老客户端炸掉；
     * `encodeDefaults`：`version` / `startHint` 这类带默认值的字段也显式发出去，快照才验得了版本。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── 编码：客户端 → 服务端 ────────────────────────────────────────────────

    fun join(playerId: String, name: String): String =
        command(RoomCommand.Join(playerId.take(MAX_ID), clean(name, MAX_NAME)))

    fun say(text: String): String = command(RoomCommand.Say(clean(text, MAX_TEXT)))

    fun ready(on: Boolean): String = command(RoomCommand.Ready(on))

    fun takeSeat(seat: Int): String = command(RoomCommand.TakeSeat(seat))

    fun swapSeats(a: Int, b: Int): String = command(RoomCommand.SwapSeats(a, b))

    fun setTableSize(size: Int): String = command(RoomCommand.SetTableSize(size))

    fun selectGame(gameId: String): String = command(RoomCommand.SelectGame(gameId))

    fun setOption(optionId: String, on: Boolean): String = command(RoomCommand.SetOption(optionId, on))

    fun kick(playerId: String): String = command(RoomCommand.Kick(playerId))

    fun start(): String = command(RoomCommand.Start)

    // ── 图片：要 / 传 ────────────────────────────────────────────────────────

    fun requestBlob(id: String): String = command(RoomCommand.RequestBlob(cleanId(id)))

    fun putBlob(
        id: String,
        index: Int,
        total: Int,
        data: ByteArray,
        role: String = ROLE_AVATAR,
    ): String = command(RoomCommand.PutBlob(cleanId(id), index, total, data, role))

    /** 服务端 → 客户端的一块图。 */
    fun blobChunk(id: String, index: Int, total: Int, data: ByteArray): String =
        BLOB + "|" + json.encodeToString(BlobChunkFrame(cleanId(id), index, total, data))

    fun blobError(id: String, reason: String): String =
        BLOB_ERR + "|" + json.encodeToString(BlobErrorFrame(cleanId(id), clean(reason, MAX_REASON)))

    private fun command(command: RoomCommand): String =
        CMD + "|" + json.encodeToString<RoomCommand>(command)

    // ── 编码：服务端 → 客户端 ────────────────────────────────────────────────

    /**
     * 整份快照。超过 [MAX_SNAPSHOT_BYTES] 时从**最老的聊天**开始丢：
     * 座位/设置/人永远是完整的，丢的只会是聊天记录。
     */
    fun snapshot(snapshot: RoomSnapshot): String {
        var messages = snapshot.messages
        while (true) {
            val line = SNAP + "|" + json.encodeToString(snapshot.copy(messages = messages))
            if (messages.size <= 1 || line.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) return line
            messages = messages.subList(1, messages.size)
        }
    }

    fun error(reason: String): String = ERROR + "|" + json.encodeToString(clean(reason, MAX_REASON))

    fun bye(reason: String): String = BYE + "|" + json.encodeToString(clean(reason, MAX_REASON))

    // ── 解码 ────────────────────────────────────────────────────────────────

    /** 解析失败一律返回 `null`：坏包不该让谁的进程崩，也不该改动任何状态。 */
    fun decodeCommand(line: String): RoomCommand? {
        val (type, payload) = head(line)
        if (type != CMD) return null
        return runCatching { json.decodeFromString<RoomCommand>(payload) }.getOrNull()
    }

    fun decodeEvent(line: String): RoomEvent? {
        val (type, payload) = head(line)
        return when (type) {
            SNAP -> runCatching { json.decodeFromString<RoomSnapshot>(payload) }
                .getOrNull()
                ?.takeIf { it.version == VERSION }
                ?.let { RoomEvent.Snapshot(it) }

            ERROR -> decodeReason(payload)?.let { RoomEvent.Error(it) }

            BYE -> decodeReason(payload)?.let { RoomEvent.Bye(it) }

            BLOB -> decodeBlob(payload)

            BLOB_ERR -> runCatching { json.decodeFromString<BlobErrorFrame>(payload) }
                .getOrNull()
                ?.let { RoomEvent.BlobError(it.id, it.reason) }

            else -> null // 不认识的类型：版本不一致时也别炸
        }
    }

    private fun decodeBlob(payload: String): RoomEvent.Blob? {
        val frame = runCatching { json.decodeFromString<BlobChunkFrame>(payload) }.getOrNull() ?: return null
        if (frame.total <= 0 || frame.index !in 0 until frame.total) return null
        if (frame.data.isEmpty() || frame.data.size > BLOB_CHUNK_BYTES) return null
        return RoomEvent.Blob(frame.id, frame.index, frame.total, frame.data)
    }

    private fun decodeReason(payload: String): String? =
        runCatching { json.decodeFromString<String>(payload) }.getOrNull()

    // ── 切分 / 清洗 ─────────────────────────────────────────────────────────

    /** `类型|剩下的一切`。没有竖线、或者负载为空，都算坏包（返回空类型）。 */
    private fun head(line: String): Pair<String, String> {
        val index = line.indexOf('|')
        if (index <= 0 || index == line.lastIndex) return "" to ""
        return line.substring(0, index) to line.substring(index + 1)
    }

    /** 去掉换行（行协议不允许）并按上限截断。转义交给 JSON，这里只管业务上限。 */
    private fun clean(value: String, max: Int): String =
        value.replace('\r', ' ').replace('\n', ' ').trim().take(max)

    /** 图片 id 只留安全字符：内容 id 是 sha1 十六进制，`builtin:` 的素材 id 也放行。 */
    private fun cleanId(value: String): String =
        value.trim().take(MAX_BLOB_ID).filter { it.isLetterOrDigit() || it == ':' || it == '.' || it == '_' || it == '-' }
}
