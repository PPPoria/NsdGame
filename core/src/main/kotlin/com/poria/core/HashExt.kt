package com.poria.core

/**
 * MurmurHash3 32-bit finalizer，把任意对象的 `hashCode()` 打散。
 *
 * 注意：结果只保证「同一个 JVM 生命周期内、同一个对象内容」稳定。
 * 不要把它当持久化 ID 存进存档 —— 存稳定 ID 请显式给每个对象一个 id 字段。
 */
fun Any.scrambleHash(): Int {
    var h = hashCode().toLong()
    h = h xor (h ushr 16)
    h *= 0xf07a3ecb
    h = h xor (h ushr 13)
    h *= 0xc2b2ae35
    h = h xor (h ushr 16)
    return h.toInt()
}
