package com.ppporia.nsdgames.ui.azul

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.poria.base.R as BaseR
import com.poria.base.ext.dp2px
import com.ppporia.nsdgames.R

/**
 * 中央区：从盘子上被挑剩、掉到桌子中间的那堆砖。
 *
 * 两种形态，按宽度自己切：
 * - **正常**：一排砖，高度 = 一格（[tileSize]）；
 * - **统计**：砖多到一排放不下，就变成"色块 × 数量"的小块。五种颜色**全列**（没出现的也算 0），
 *   位置稳定，不会因为剩砖变化而左右跳。
 *
 * 两种形态的**最右端都跟着一块先手砖**（见 [bind] 的 `firstAct`）：
 * 它还在桌上，就意味着"现在去中央拿砖的人会把它一起带走"。
 * 用砖本身表示比挂一个「先手」文字标签直观——而且它本来就是要进惩罚区的那个格子。
 */
class AzulRedundantLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 一排砖的高度；由对局页按"我的砖"的实测边长喂进来。 */
    var tileSize: Int = 0
        set(value) {
            if (field != value) {
                field = value
                rebuild()
            }
        }

    private val gap = context.dp2px(GAP_DP)

    private var tiles: List<AzulTilePaint> = emptyList()
    private var palette: List<AzulTilePaint> = emptyList()
    private var firstAct: AzulTilePaint? = null
    private var lastWidth = -1

    /** 这一轮 rebuild 造出来的砖（格子 + 它的 paint），高亮靠它。 */
    private val shown = ArrayList<Pair<AzulTileCell, AzulTilePaint>>()

    /** 可以点的 View（砖本体，或统计块整块）+ 它的 paint。 */
    private val clickTargets = ArrayList<Pair<View, AzulTilePaint>>()

    /** 点中央区的某一种砖（统计形态下点的是那个统计块）。 */
    var onTileClick: ((paint: AzulTilePaint) -> Unit)? = null
        set(value) {
            field = value
            applyTileClick()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
    }

    /**
     * @param tiles 中央区剩下的砖（**不含**先手砖）
     * @param palette 五种颜色，顺序固定（统计形态按这个顺序列）
     * @param firstAct 桌上还有先手砖时传它的 paint，否则 null
     */
    fun bind(tiles: List<AzulTilePaint>, palette: List<AzulTilePaint>, firstAct: AzulTilePaint?) {
        this.tiles = tiles
        this.palette = palette
        this.firstAct = firstAct
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 切不切统计形态取决于宽度，宽度要等布局完才知道
        if (w != lastWidth) {
            lastWidth = w
            rebuild()
        }
    }

    private fun rebuild() {
        removeAllViews()
        shown.clear()
        clickTargets.clear()
        if (width <= 0 || tileSize <= 0) return // 还没量过尺寸：等尺寸到位再来一遍

        // 两种形态高度都按"一排砖"算：切到统计形态时公共区不会突然变矮、把上面的盘子挤大
        minimumHeight = tileSize

        val token = firstAct
        val total = tiles.size + if (token != null) 1 else 0
        val tileModeWidth = if (total == 0) 0 else total * tileSize + (total - 1) * gap
        val statMode = tileModeWidth > width

        if (statMode) addStats() else addTileRow()

        if (token != null) {
            val size = if (statMode) {
                (tileSize * 0.78f).toInt().coerceAtLeast(context.dp2px(10))
            } else tileSize
            // 先手砖**不可点**：它不是一种可以"拿"的颜色，点它发不出合法命令
            addTileCell(token, size, 0, selectable = false)
        }
        applyTileClick()
    }

    /** 高亮选中那一色（统计形态下亮的是统计块里的色块）。 */
    fun setSelected(paint: AzulTilePaint?) {
        shown.forEach { (cell, shownPaint) ->
            cell.isHighlight = paint != null && shownPaint == paint
        }
    }

    private fun applyTileClick() {
        val callback = onTileClick
        clickTargets.forEach { (view, paint) ->
            view.setOnClickListener(
                if (callback == null) null
                else View.OnClickListener { callback.invoke(paint) }
            )
        }
    }

    private fun addTileRow() {
        tiles.forEachIndexed { index, paint ->
            val last = index == tiles.lastIndex
            addTileCell(paint, tileSize, if (last) 0 else gap)
        }
    }

    private fun addStats() {
        palette.forEachIndexed { index, paint ->
            val count = tiles.count { it == paint }
            addView(
                makeStatBlock(paint, count, tileSize),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = if (index == palette.lastIndex) 0 else gap * 2
                    gravity = Gravity.CENTER_VERTICAL
                }
            )
        }
    }

    private fun makeStatBlock(paint: AzulTilePaint, count: Int, size: Int): View {
        val block = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val swatchSize = (size * 0.55f).toInt().coerceAtLeast(context.dp2px(8))
        val swatchCell = AzulTileCell(block).apply {
            backgroundColor = paint.color
            bitmap = paint.bitmap
            cornerRadius = swatchSize * AzulTileCell.CELL_CORNER_RATIO
            view.alpha = if (count > 0) 1f else 0.35f
        }
        block.addView(swatchCell.view, LayoutParams(swatchSize, swatchSize))

        val label = TextView(context).apply {
            text = context.getString(R.string.azul_tile_rest, count)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(BaseR.dimen.text_caption)
            )
            setTextColor(
                MaterialColors.getColor(this, android.R.attr.textColorSecondary, 0xFF8C8C8C.toInt())
            )
        }
        block.addView(
            label,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp2px(2)
            }
        )

        // 高亮的是色块（小方块），点的是整块：点"某种颜色"就等于去中央拿那一色
        shown.add(swatchCell to paint)
        clickTargets.add(block to paint)
        return block
    }

    private fun addTileCell(
        paint: AzulTilePaint,
        size: Int,
        marginEnd: Int,
        selectable: Boolean = true,
    ) {
        val cell = AzulTileCell(this)
        cell.backgroundColor = paint.color
        cell.bitmap = paint.bitmap
        cell.text = paint.label
        cell.cornerRadius = size * AzulTileCell.CELL_CORNER_RATIO
        cell.view.layoutParams = LayoutParams(size, size).apply { this.marginEnd = marginEnd }
        addView(cell.view)
        shown.add(cell to paint)
        if (selectable) clickTargets.add(cell.view to paint)
    }

    companion object {
        private const val GAP_DP = 4
    }
}
