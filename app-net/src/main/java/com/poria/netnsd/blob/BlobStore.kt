package com.poria.netnsd.blob

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * 内容寻址的小文件仓库：**id = sha1(内容) 的 40 位小写十六进制**。
 *
 * 房主和客户端共用同一份实现，`BlobChannel`（谁有谁发）的"谁有"就是它：
 * - 收分块：[put] 逐块写临时文件，最后一块验 hash 通过才转成正式文件；
 * - 给分块：[file] 拿到文件，`BlobChannel` 按 [BlobLimits.CHUNK_BYTES] 切着发；
 * - 自己装进来的：[install]（用户选的头像、打包素材之类，不经网络）。
 *
 * 三条约束：
 * 1. **id 只认 sha1 十六进制**，文件名就是 id——顺手挡掉 `../` 这种路径穿越；
 * 2. 分块必须**按顺序**来（同一条 TCP 连接本来就保序），乱序/超长/总块数超限一律拒绝；
 * 3. 总占用按 LRU 配额淘汰（[quotaBytes]），正在被读的文件在 Android 上删了也不影响
 *    已经打开的流（Linux 语义），所以不用为"正在发"单独留后门。
 */
class BlobStore(
    private val dir: File,
    private val quotaBytes: Long = BlobLimits.STORE_BYTES,
) {

    private class Upload(val total: Int, val temp: File) {
        /** 下一块应该是第几块。 */
        var next = 0
    }

    private val lock = Any()

    /** id → 正式文件。LinkedHashMap 的迭代顺序就是 LRU 顺序（最老的在前）。 */
    private val blobs = LinkedHashMap<String, File>()

    private val uploads = HashMap<String, Upload>()
    private val outputs = HashMap<String, FileOutputStream>()

    init {
        dir.mkdirs()
        dir.listFiles()
            ?.filter { isValidId(it.name) && it.isFile }
            ?.sortedBy { it.lastModified() }
            ?.forEach { blobs[it.name] = it }
    }

    val size: Int get() = synchronized(lock) { blobs.size }

    fun contains(id: String): Boolean = synchronized(lock) { blobs.containsKey(id.lowercase()) }

    /** 完整的 blob 文件；没有（或正在上传）返回 null。会给它 bump 一次 LRU。 */
    fun file(id: String): File? = synchronized(lock) {
        val clean = id.lowercase()
        val file = blobs[clean]?.takeIf { it.exists() } ?: return null
        blobs.remove(clean)
        blobs[clean] = file
        file
    }

    fun open(id: String): InputStream? = file(id)?.let { runCatching { it.inputStream() }.getOrNull() }

    /**
     * 收一块（下载拼装走它）。
     *
     * @return `true` = 这份 blob 已经完整，可以 [file]/[open] 了
     * @return `false` = 还没收齐，或者这块被拒绝（调用方应把整份传输当作失败）
     */
    fun put(id: String, index: Int, total: Int, data: ByteArray): Boolean {
        val clean = id.lowercase()
        if (!isValidId(clean)) return false
        if (total <= 0 || total > BlobLimits.MAX_CHUNKS) return false
        if (data.isEmpty() || data.size > BlobLimits.CHUNK_BYTES) return false

        synchronized(lock) {
            // 已经有了：重复回应/重复下载，直接当完成
            if (blobs.containsKey(clean)) return true

            val upload = uploads[clean] ?: startUpload(clean, total) ?: return false
            if (upload.total != total || index != upload.next) {
                abortUpload(clean)
                return false
            }

            val output = outputs[clean] ?: run {
                abortUpload(clean)
                return false
            }
            val written = runCatching { output.write(data) }.isSuccess
            if (!written) {
                abortUpload(clean)
                return false
            }

            upload.next++
            if (upload.next < total) return false

            uploads.remove(clean)
            outputs.remove(clean)?.let { runCatching { it.close() } }
            return finish(clean, upload.temp)
        }
    }

    /**
     * 本机直接放进一份（自己选的头像、打包素材之类，不经网络）。
     *
     * @return 是否已可用（失败说明 id 不合法或落盘出错）
     */
    fun install(id: String, bytes: ByteArray): Boolean {
        val clean = id.lowercase()
        if (!isValidId(clean) || bytes.isEmpty()) return false
        if (bytes.size > BlobLimits.MAX_BYTES) return false

        synchronized(lock) {
            if (blobs.containsKey(clean)) return true
            return runCatching {
                dir.mkdirs()
                val target = File(dir, clean)
                val temp = File(dir, "$clean.part")
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    target.writeBytes(bytes)
                    temp.delete()
                }
                blobs[clean] = target
                evictLocked()
                true
            }.getOrElse { false }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun startUpload(clean: String, total: Int): Upload? {
        if (dir.mkdirs() && !dir.exists()) return null
        val temp = File(dir, "$clean.part")
        return runCatching {
            outputs[clean] = FileOutputStream(temp, false)
            Upload(total, temp).also { uploads[clean] = it }
        }.getOrNull()
    }

    private fun abortUpload(clean: String) {
        uploads.remove(clean)
        outputs.remove(clean)?.let { runCatching { it.close() } }
        runCatching { File(dir, "$clean.part").delete() }
    }

    /** 收齐了：验 sha1，通过才转正。 */
    private fun finish(clean: String, temp: File): Boolean {
        val ok = runCatching { sha1Hex(temp.readBytes()) == clean }.getOrElse { false }
        if (!ok) {
            runCatching { temp.delete() }
            return false
        }

        val target = File(dir, clean)
        if (!temp.renameTo(target)) {
            if (!runCatching { target.writeBytes(temp.readBytes()) }.isSuccess) {
                runCatching { temp.delete() }
                return false
            }
            runCatching { temp.delete() }
        }

        blobs[clean] = target
        evictLocked()
        return true
    }

    private fun evictLocked() {
        if (blobs.isEmpty()) return
        var used = blobs.values.sumOf { it.length() }
        val iterator = blobs.entries.iterator()
        while (used > quotaBytes && iterator.hasNext()) {
            val entry = iterator.next()
            used -= entry.value.length()
            runCatching { entry.value.delete() }
            iterator.remove()
        }
    }

    companion object {

        private val ID = Regex("[0-9a-f]{40}")

        /** 是不是一个合法的内容 id。**只有 sha1 十六进制**——文件名就是它，顺便挡路径穿越。 */
        fun isValidId(id: String): Boolean = ID.matches(id)

        /** `builtin:xxx` 表示"打包在 APK 里的素材"，不走网络，由 UI 直接从资源加载。 */
        fun isBuiltin(id: String): Boolean = id.startsWith("builtin:")

        fun sha1Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun sha1Hex(file: File): String = sha1Hex(file.readBytes())
    }
}
