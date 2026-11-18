package com.poria.net.proto

import kotlinx.serialization.json.Json

/**
 * 协议专用 JSON 配置。唯一实例，全部编解码器共用。
 *
 * 归属：传输与消息协议。
 *
 * - `ignoreUnknownKeys = true`：ARCHITECTURE.md §4.4 —— 前向兼容，老端遇到新字段不炸。
 * - `encodeDefaults = true`：字段始终写出，抓包/日志里看得见（调试期最值钱的属性）。
 * - `explicitNulls = false`：`null` 字段不写出，省字节；缺省字段由数据类默认值兜住。
 */
object ProtocolJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
