package com.poria.netnsd.blob

import com.poria.netnsd.link.LinkOut
import com.poria.netnsd.link.PlayerRef
import com.poria.netnsd.link.TcpHandler
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 图片频道（`ns = "blob"`）：**谁有谁发**，房主只做中转、不存别人的图。
 *
 * ```
 * A 缺图：  out.sendTo(ownerId, want(id))     ← 房主侧直投，客人侧自动中转
 * 拥有者：  out.sendTo(A, chunk(id, i, total, size, data))   ← 3KB 一块
 * A 收齐：  BlobStore 验 sha1 → progress 里那一条消失 → ready 里出现 id
 * ```
 *
 * 为什么"谁有谁发"成立：id 就是内容的 sha1，接收方拼完自己验一遍，
 * 所以发送方是谁都行、房主不可信也传不坏；房主因此不用替所有人存图。
 *
 * ## 进度
 * 每收到一块就更新 [progress]（`received / total` 字节数，顺手给了 `percent`），
 * 下载完那条就从 [progress] 里移除、并往 [ready] 里发一次 id，同时进 [available]（本机能画了）。
 * 要不到时进 [failed]。UI 只要 `progress.collect { }` + 一次 [file] 取文件就行。
 *
 * ## 线程
 * `onCommand` / `onEvent` 在链路的读线程上跑；拼装（3KB 写盘）就在那儿做，量级可以接受。
 * **发送**在频道自己的 IO 协程里跑，且同一 `(收件人, id)` 只允许一条流——两条流交错会打乱
 * 块序，`BlobStore` 会整份放弃。
 */
class BlobChannel(private val store: BlobStore) : TcpHandler {

    override val ns: String = NS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var out: LinkOut? = null

    private val lock = Any()

    /** 正在下载的图：id → 进度。收齐/失败后移除。 */
    private val _progress = MutableStateFlow<Map<String, BlobProgress>>(emptyMap())
    val progress: StateFlow<Map<String, BlobProgress>> = _progress.asStateFlow()

    /**
     * 本机**已经能画**的图 id：自己装进来的 + 收齐的。
     *
     * 快照里只带头像的 id，UI 拿它判断"能不能立刻取文件"；不在里面就先画占位、发一次 [request]。
     */
    private val _available = MutableStateFlow<Set<String>>(emptySet())
    val available: StateFlow<Set<String>> = _available.asStateFlow()

    /** 刚刚收齐的图 id（带 replay，晚订阅也能拿到最近几条）。 */
    private val _ready = MutableSharedFlow<String>(
        replay = READY_REPLAY,
        extraBufferCapacity = 16
    )
    val ready: SharedFlow<String> = _ready.asSharedFlow()

    /** 要不到的图（对方没有、对方出错）。 */
    private val _failed = MutableSharedFlow<BlobFailure>(
        replay = READY_REPLAY,
        extraBufferCapacity = 16
    )
    val failed: SharedFlow<BlobFailure> = _failed.asSharedFlow()

    /** 正在发给某人的流：`"<收件人>|<id>"` → Job，保证同一张图对同一个人只有一条流。 */
    private val serving = ConcurrentHashMap<String, Job>()

    override fun attach(out: LinkOut) {
        this.out = out
    }

    // ── 本机这一侧的 API ────────────────────────────────────────────────────

    fun contains(id: String): Boolean = store.contains(id)

    /** 本机可用的图片文件；没有 / 还在下载返回 `null`。 */
    fun file(id: String): File? = store.file(id)

    /**
     * 把一张图装进本机仓库（用户选的头像、打包素材之类）。
     *
     * @return 内容 id（sha1）；空图 / 超限返回 `null`
     */
    fun install(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > BlobLimits.MAX_BYTES) return null
        val id = BlobStore.sha1Hex(bytes)
        if (!store.install(id, bytes)) return null
        _available.update { it + id }
        return id
    }

    /**
     * 向拥有者要一张图。本机已有、或者已经在拉都返回 `false`（重复调用是无害的空转）。
     *
     * @return 是否真的把 `want` 发给了对方
     */
    fun request(id: String, ownerId: String): Boolean {
        if (id.isEmpty() || ownerId.isEmpty() || !BlobStore.isValidId(id)) return false
        if (store.contains(id)) return false

        synchronized(lock) {
            if (_progress.value.containsKey(id)) return false
            _progress.update { it + (id to BlobProgress(id, 0, 0)) }
        }

        if (out?.sendTo(ownerId, BlobCodec.encode(BlobPayload.Want(id))) != true) {
            synchronized(lock) {
                _progress.update { it - id }
            }
            return false
        }

        // 对方一直不回（掉线/被配额挤掉）：把占位撤掉，下次还能再要
        scope.launch {
            delay(TIMEOUT_MS)
            val pending = synchronized(lock) {
                val current = _progress.value[id]
                if (current != null) _progress.update { it - id }
                current
            }
            if (pending != null) _failed.tryEmit(BlobFailure(id, "等太久了"))
        }
        return true
    }

    // ── TcpHandler ─────────────────────────────────────────────────────────

    /** 服务端方向：某个客人要图（房主自己就是拥有者的情况）。 */
    override fun onCommand(player: PlayerRef, payload: kotlinx.serialization.json.JsonElement?) {
        handle(player.id, payload)
    }

    /** 客户端方向：中转来的 `want`、以及拥有者推回来的 `chunk`。 */
    override fun onEvent(payload: kotlinx.serialization.json.JsonElement?, from: String?) {
        val sender = from ?: return // from 为空 = 房主自己推的，blob 频道用不上
        handle(sender, payload)
    }

    override fun onLinkClosed(reason: String?) {
        scope.cancel()
        serving.clear()
    }

    // ── 收 ─────────────────────────────────────────────────────────────────

    private fun handle(senderId: String, payload: kotlinx.serialization.json.JsonElement?) {
        when (val msg = BlobCodec.decode(payload) ?: return) {
            is BlobPayload.Want -> serve(senderId, msg.id)
            is BlobPayload.Chunk -> receive(msg)
            is BlobPayload.Missing -> fail(msg.id, "对方没有这张图")
            is BlobPayload.Failed -> fail(msg.id, msg.reason)
        }
    }

    private fun receive(chunk: BlobPayload.Chunk) {
        if (store.contains(chunk.id)) return // 已经有了：重复回应，忽略

        val known = synchronized(lock) { _progress.value[chunk.id] } ?: return // 没要过（或已超时），别弄脏进度表

        val received = known.received + chunk.data.size
        synchronized(lock) {
            _progress.update {
                it + (chunk.id to BlobProgress(
                    chunk.id,
                    received,
                    chunk.size.toLong()
                ))
            }
        }

        if (store.put(chunk.id, chunk.index, chunk.total, chunk.data)) {
            synchronized(lock) { _progress.update { it - chunk.id } }
            _available.update { it + chunk.id }
            _ready.tryEmit(chunk.id)
        }
    }

    private fun fail(id: String, reason: String) {
        val pending = synchronized(lock) {
            val current = _progress.value[id]
            if (current != null) _progress.value = _progress.value - id
            current
        }
        if (pending != null) _failed.tryEmit(BlobFailure(id, reason))
    }

    // ── 发 ─────────────────────────────────────────────────────────────────

    private fun serve(requester: String, id: String) {
        if (!store.contains(id)) {
            out?.sendTo(requester, BlobCodec.encode(BlobPayload.Missing(id)))
            return
        }

        val key = "$requester|$id"
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                stream(requester, id)
            } finally {
                serving.remove(key)
            }
        }
        if (serving.putIfAbsent(key, job) != null) {
            job.cancel()
            return // 已经在发了：再起一条会打乱块序
        }
        job.start()
    }

    private fun stream(requester: String, id: String) {
        val file = store.file(id) ?: return
        forEachChunk(file) { index, total, size, bytes ->
            val payload = BlobPayload.Chunk(id, index, total, size, bytes)
            out?.sendTo(requester, BlobCodec.encode(payload)) == true
        }
    }

    companion object {

        /** 频道名：用 `TcpLink.register(BlobChannel(...))` 注册。 */
        const val NS = "blob"

        /** 等一张图最多等多久；超时就把占位撤掉并记一条 [failed]。 */
        private const val TIMEOUT_MS = 30_000L

        /** `ready` / `failed` 各留最近几条，晚订阅的 UI 也能拿到。 */
        private const val READY_REPLAY = 16
    }
}

/**
 * 一张图的下载进度。
 *
 * `total = 0` 表示还没收到第一块、还不知道总大小；收到第一块之后 `total` 就是整张图的字节数。
 */
data class BlobProgress(
    val id: String,
    val received: Long,
    val total: Long,
) {
    /** 0f..1f；总大小未知时是 0。 */
    val fraction: Float
        get() = if (total <= 0L) 0f else (received.toFloat() / total).coerceIn(
            0f,
            1f
        )

    /** 0..100 的整数百分比，UI 直接显示。 */
    val percent: Int get() = (fraction * 100).toInt()

    val done: Boolean get() = total > 0 && received >= total
}

/** 要不到图的原因。 */
data class BlobFailure(val id: String, val reason: String)
