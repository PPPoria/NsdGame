package com.poria.netnsd.game

import com.poria.game.azul.AzulSnapshot
import com.poria.game.azul.Tile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface AzulPayload {
    /**
     * 客户端 给 服务端：我选择了一个盘子
     * 我取走的种类是 tile ，这个盘子有 list（用于匹配盘子），我要把我取走的放在我的 floor
     **/
    @Serializable
    @SerialName("plate")
    data class Plate(val tile: Tile, val list: List<Tile>, val row: Int): AzulPayload

    /**
     * 客户端 给 服务端：我在剩余的公共区域中选择，
     * 我取走的种类是 tile ，我要把我取走的放在我的 floor
     **/
    @Serializable
    @SerialName("redundant")
    data class Redundant(val tile: Tile, val row: Int): AzulPayload

    /**
     * 服务端 给 客户端：权威快照
     **/
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val snapshot: AzulSnapshot): AzulPayload
}