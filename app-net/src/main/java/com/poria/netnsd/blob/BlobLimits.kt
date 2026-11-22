package com.poria.netnsd.blob

/**
 * 图片（blob）通道的尺寸约束。房主、客户端、仓库共用这一份，改这里就是改全局。
 */
object BlobLimits {

    /**
     * 一块的原始字节数。
     *
     * 取 3KB 是因为 base64 后正好 4KB、加上 JSON 骨架约 4.2KB，**稳在 8KB 行上限以内**；
     * 同时单次阻塞写足够小，传图不会把聊天/快照卡住几百毫秒。
     */
    const val CHUNK_BYTES = 3_072

    /** 单张图的上限（512px 的头像/纹理一般 20–60KB，4MB 已经很宽松了）。 */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** 单张图最多几块（防止对端用 `total` 把上传缓冲区撑爆）。 */
    const val MAX_CHUNKS = MAX_BYTES / CHUNK_BYTES + 1

    /** 本机缓存最多留多少字节的图，超了按 LRU 淘汰。 */
    const val STORE_BYTES = 64L * 1024 * 1024

    /** 内容 id（sha1 十六进制）最长多少。 */
    const val MAX_ID = 64
}
