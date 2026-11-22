package com.poria.netnsd.blob

import com.poria.netnsd.Session
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 图片的**唯一**用法：按内容 id 取文件、按 id + 拥有者去拉、看进度。
 *
 * 这里刻意不提"头像""花纹"这些业务——谁拥有这张图是**调用方的事**：
 * 房间页看 `RoomSnapshot.players[].avatar`，对局页看自己的对局快照。页面把 `(id, ownerId)`
 * 递进来就行，之后的拉取、进度、缓存全归 `BlobChannel`。**不要**再包一层"房间版/对局版图片 API"：
 * blob 频道从头到尾只有一个，业务语义只体现在"谁是 owner"这一句上。
 *
 * 全进程也只有**一个** `BlobChannel`（`Session` 持有）：它按 `ns = "blob"` 挂在链路上，
 * 而且磁盘仓库是"一份索引 + 一份 `.part` 临时文件"，两个实例同目录会互相踩坏。
 * 所以后续页面是**订阅**它，不是新建一个。
 */

/** 本机已经能画的图 id：自己装进来的 + 收齐的。没连上时是空集（永远不会变）。 */
val Session.images: StateFlow<Set<String>>
    get() = connection?.blob?.available ?: NO_IMAGES

/** 在途的下载进度：id → 字节数/百分比。没连上时是空表。 */
val Session.imageProgress: StateFlow<Map<String, BlobProgress>>
    get() = connection?.blob?.progress ?: NO_PROGRESS

/** 本机可用的图片文件；没有（或还在下载）返回 `null`——UI 先画占位。 */
fun Session.imageFile(id: String): File? = connection?.blob?.file(id)

/**
 * 向拥有者要一张图。本机已有、已经在拉、`builtin:` 素材都直接返回 `false`。
 *
 * @return 是否真的把 `want` 发了出去
 */
fun Session.requestImage(id: String, ownerId: String): Boolean {
    val blob = connection?.blob ?: return false
    if (id.isEmpty() || BlobStore.isBuiltin(id)) return false
    return blob.request(id, ownerId)
}

/** 没连上时的占位流（不会变，共享一个实例，避免每次访问都新建一个 StateFlow）。 */
private val NO_IMAGES: StateFlow<Set<String>> = MutableStateFlow(emptySet())
private val NO_PROGRESS: StateFlow<Map<String, BlobProgress>> = MutableStateFlow(emptyMap())
