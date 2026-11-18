package com.poria.net.proto

/**
 * 协议大版本（帧头 `proto` 字节 + `HELLO` 载荷同源）。
 *
 * 归属：传输与消息协议。
 *
 * 策略（ARCHITECTURE.md §4.4）：**只支持相同大版本**，不等即由会话层回
 * `REJECT(VERSION_MISMATCH)`。局域网场景下两端通常是同一个 APK 或相邻版本，
 * 不做兼容矩阵——省下来的复杂度全部留给游戏规则。
 *
 * 变更史：
 * - `1`：M0/M1 —— 帧格式 + `Envelope` + 16 个类型（1..16）。
 * - `2`：ADR-019 —— 新增 `LOBBY_REQ(17)` / `LOBBY_RESULT(18)`（大厅意图信道）。
 *
 * 升版本的触发条件（任一）：改帧头布局、改类型码表、改帧体 JSON 结构、改 `Envelope` 字段。
 * **偷偷加类型不升版本**会让"改类型表必须升版本"从规则退化成建议，等到真有 Web/iOS
 * 客户端时才集中爆炸——而那时候的修复成本是本轮的全部收益。
 */
object ProtocolVersion {

    /** 当前协议的版本号。 */
    const val CURRENT: Int = 2
}
