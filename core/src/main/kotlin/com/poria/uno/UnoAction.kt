package com.poria.uno

/**
 * 玩家意图。UI 只发这个，不发"新状态"。
 *
 * 就三个动作：出牌、摸牌、过牌。以后接服务器时，网络包体里传的也是这三个，
 * 不需要再发明一套"命令 + 载荷 + 编解码器"。
 */
sealed interface UnoAction {

    /**
     * 打出一张牌。
     *
     * @param card 要出的牌
     * @param wildColor 万能牌必填，其他牌必须为 `null`
     */
    data class Play(val card: UnoCard, val wildColor: UnoColor? = null) : UnoAction

    /** 摸一张牌。摸到的能出就留在自己回合，不能出就自动过牌。 */
    data object Draw : UnoAction

    /** 摸牌后放弃出牌。只有摸到能出的牌时才用得上。 */
    data object Pass : UnoAction
}

/**
 * 非法操作。规则判定只在这里抛，UI 拿到 `message` 直接提示玩家。
 *
 * 用异常而不是 `Result` / `Either`：规则错在这一层就是异常，
 * 而且真正会玩的 AI 永远不该撞到它——撞到了就是 bug，不该被静默吞掉。
 */
class IllegalMove(message: String) : IllegalStateException(message)
