package com.poria.game

/**
 * 一个"可以开一桌"的游戏的静态描述：叫什么、几个人玩、有哪些自定义玩法。
 *
 * 这份数据只描述**怎么摆桌子**，不描述规则。规则引擎将来放在这个模块的别的包里
 * （比如 `AzulEngine`），大厅层（`:app-net`）只拿它做校验：
 * 选了这个游戏，人数和玩法开关就得在它给的范围内。
 */
data class GameInfo(
    val id: String,
    val title: String,
    val subtitle: String,
    val minPlayers: Int,
    val maxPlayers: Int,
    /** 对局规则做好了没有。没做好的游戏可以进房间排座、设玩法，但「开始」会被服务端挡下。 */
    val available: Boolean,
    val options: List<GameOption> = emptyList(),
)

/** 一个自定义玩法开关（布尔）。[id] 会随快照送到所有客户端，定了就别随便改。 */
data class GameOption(
    val id: String,
    val title: String,
    val description: String,
    val default: Boolean = false,
)

/**
 * 游戏目录。**新增游戏只改这个文件**：加一条 [GameInfo]，房间页的
 * "选游戏 / 选人数 / 玩法开关"都会自动跟着变（它们都是照着这份列表渲染的）。
 */
object GameCatalog {

    /** 整个应用允许的牌桌上限，具体游戏的 [GameInfo.maxPlayers] 只能比它小。 */
    const val MAX_TABLE = 6

    val azul = GameInfo(
        id = "azul",
        title = "花砖物语",
        subtitle = "Azul · 轮流拿砖、铺墙、结算",
        minPlayers = 2,
        maxPlayers = 4,
        // 规则还没写：房间能排座、能设玩法、能互相同步，但房主按「开始」会被服务端挡下。
        available = false,
        options = listOf(
            GameOption(
                id = "grey",
                title = "灰色棋盘面",
                description = "用玩家板背面的灰色面开局，图案更花",
            ),
            GameOption(
                id = "quick",
                title = "快速局",
                description = "只打 3 轮，时间砍掉一半",
            ),
            GameOption(
                id = "harsh",
                title = "地板加倍",
                description = "掉进地板区的砖，扣分翻倍",
            ),
        ),
    )

    /** 房间页按这个顺序显示可选游戏。 */
    val games: List<GameInfo> = listOf(azul)

    fun byId(id: String): GameInfo? = games.firstOrNull { it.id == id }

    fun option(gameId: String, optionId: String): GameOption? =
        byId(gameId)?.options?.firstOrNull { it.id == optionId }

    /** 新开一桌时的默认玩法开关。 */
    fun defaultOptions(game: GameInfo): Map<String, Boolean> =
        game.options.associate { it.id to it.default }

    /** 新开一桌时的默认座位数：先按这个游戏能坐满算，但不超过 [MAX_TABLE]。 */
    fun defaultTableSize(game: GameInfo): Int =
        game.maxPlayers.coerceIn(game.minPlayers, MAX_TABLE)
}
