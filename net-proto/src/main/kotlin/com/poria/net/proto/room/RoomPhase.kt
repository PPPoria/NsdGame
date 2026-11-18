package com.poria.net.proto.room

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 房间阶段。**FROZEN**（ARCHITECTURE.md §12.1；归属：架构师；签名审批：架构师）。
 *
 * 为什么是 enum 而不是字符串常量（ADR-015）：
 * 1. **编译期穷尽**。`RoomPhase` 会被四处 `when`：会话状态机、NSD 房间列表、对局页、
 *    以及测试里的断言。字符串常量写错一个字母是运行时 bug，enum 是编译错误。
 * 2. **单一真相**。[wireValue] 同时被 **NSD TXT 记录**和**加入握手的 JSON** 使用。
 *    如果两处各写一份字面量，"发现列表显示 Lobby、真正加入却被拒为 InPlay" 这种
 *    极难复现的不一致就会出现。
 * 3. JSON 用 [SerialName] 与线值对齐，因此重命名 Kotlin 常量不会悄悄改线格式。
 *
 * **新增一个阶段 = 改线格式 = 必须升 [com.poria.net.proto.ProtocolVersion.CURRENT]。**
 * 老端收到未定义阶段时的行为由会话层决定（当前策略：拒绝加入并回 `REJECT`，不猜）。
 */
@Serializable
enum class RoomPhase(val wireValue: String) {

    /** 大厅：可自由进出，座位可分配。 */
    @SerialName("Lobby")
    LOBBY("Lobby"),

    /** 对局中：座位冻结（D2）。是否允许中途加入由会话层策略决定。 */
    @SerialName("Playing")
    PLAYING("Playing"),

    /** 终局：等待房主决定再来一局或解散。 */
    @SerialName("Finished")
    FINISHED("Finished"),
    ;

    companion object {

        /** NSD TXT 与外部实现用的解析入口；大小写不敏感，未知值返回 null（**不抛异常**，由调用方决定策略）。 */
        fun fromWire(value: String): RoomPhase? =
            entries.firstOrNull { it.wireValue.equals(value.trim(), ignoreCase = true) }
    }
}
