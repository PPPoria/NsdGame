package com.poria.uno

/** 四种颜色。万能牌（[UnoKind.WILD] / [UnoKind.WILD4]）没有颜色，出牌时才由玩家指定。 */
enum class UnoColor(val label: String) {
    RED("红"),
    YELLOW("黄"),
    GREEN("绿"),
    BLUE("蓝"),
}

/**
 * 牌面种类：数字牌 N0~N9，其余是功能牌。
 *
 * 用 enum 而不是 sealed class，是因为规则判定只关心"是不是同一张牌"和"是不是万能牌"，
 * 十几种牌不值得各自一个类。
 */
enum class UnoKind(val label: String) {
    N0("0"), N1("1"), N2("2"), N3("3"), N4("4"),
    N5("5"), N6("6"), N7("7"), N8("8"), N9("9"),
    SKIP("⊘"), REVERSE("⇄"), DRAW2("+2"), WILD("变色"), WILD4("+4");

    /** 万能牌：能接任何牌，出牌时必须指定新颜色。 */
    val isWild: Boolean get() = this == WILD || this == WILD4

    /** 数字牌返回 0~9，功能牌返回 null。 */
    val digit: Int? get() = if (ordinal <= N9.ordinal) ordinal else null
}

/**
 * 一张牌。
 *
 * [color] 为 `null` 表示万能牌——这是唯一一处"牌没有颜色"，别的地方别再加可空字段。
 */
data class UnoCard(val color: UnoColor?, val kind: UnoKind) {

    /** 画牌时用的底色：万能牌借用桌面上当前生效的颜色。 */
    fun colorOnTable(tableColor: UnoColor): UnoColor = color ?: tableColor
}

/**
 * 一整副 UNO 牌 108 张：
 * 四色各 25 张（0 一张、1~9 各两张、跳过/反转/+2 各两张），外加 4 张变色、4 张 +4。
 */
fun freshUnoDeck(): List<UnoCard> {
    val deck = ArrayList<UnoCard>(108)
    for (color in UnoColor.entries) {
        deck += UnoCard(color, UnoKind.N0)
        for (kind in UnoKind.entries) {
            if (kind.isWild || kind == UnoKind.N0) continue
            deck += UnoCard(color, kind)
            deck += UnoCard(color, kind)
        }
    }
    repeat(4) {
        deck += UnoCard(null, UnoKind.WILD)
        deck += UnoCard(null, UnoKind.WILD4)
    }
    return deck
}
