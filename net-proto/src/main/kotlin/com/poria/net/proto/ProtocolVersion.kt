package com.poria.net.proto

/**
 * 协议大版本（帧头 `proto` 字节 + `HELLO` 载荷同源）。
 *
 * 归属：传输与消息协议。
 *
 * 策略（ARCHITECTURE.md §4.4）：**只支持相同大版本**，不等即由会话层回
 * `REJECT(VERSION_MISMATCH)`。局域网场景下两端通常是同一个 APK 或相邻版本，
 * 不做兼容矩阵——省下来的复杂度全部留给游戏规则。
 */
object ProtocolVersion {

    /** 当前协议的版本号。改帧格式 / 改类型表 / 改信封字段 → 必须 +1。 */
    const val CURRENT: Int = 1
}
