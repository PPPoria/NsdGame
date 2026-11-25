package com.ppporia.nsdgames.ui.azul

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.ppporia.nsdgames.R

/**
 * 一个工厂盘：圆盘上摆砖，**2×2 四个位置固定**。
 *
 * 为什么是 2×2 而不是"一排四个"：一排四个时砖的边长只能到盘径的 1/4 上下（盘要装下 4 砖宽），
 * 2×2 时盘径只要 2 砖宽，同一块地方砖能大一倍——竖屏里这正好补上"场上的砖别比我棋盘上的小"。
 *
 * 位置是死的、砖是活的：外面给一个 List，这里按序填前 min(4, size) 个位置，
 * **不校验数量**（牌袋见底时 1~3 块都是合法的，第 4 个位置就空着）。
 */
class AzulPlateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val cells = List(MAX_TILES) { AzulTileCell(this) }

    /** 这一盘当前的砖（和 [cells] 一一对应），点选和高亮都靠它。 */
    private var bound: List<AzulTilePaint> = emptyList()

    /**
     * 点盘里的某一块砖。**只有设了它，砖才能点**：
     * 两步选砖的第一步就是"点一块砖"——砖本身已经带上了颜色和所属盘子，不用再挑一遍。
     */
    var onTileClick: ((paint: AzulTilePaint) -> Unit)? = null
        set(value) {
            field = value
            cells.forEachIndexed { index, cell ->
                cell.view.setOnClickListener(
                    if (value == null) null
                    else View.OnClickListener { bound.getOrNull(index)?.let(value) }
                )
            }
        }

    /** 盘里那块砖的边长（px）。 */
    var tileSize: Int = 0
        private set

    init {
        cells.forEach { addView(it.view) }
        setBackgroundResource(R.drawable.bg_azul_plate)
    }

    /** 按序摆：0 左上、1 右上、2 左下、3 右下。 */
    fun bind(tiles: List<AzulTilePaint>) {
        bound = tiles
        cells.forEachIndexed { index, cell ->
            val paint = tiles.getOrNull(index)
            cell.isVisible = paint != null
            if (paint != null) {
                cell.backgroundColor = paint.color
                cell.bitmap = paint.bitmap
                cell.text = paint.label
            }
            cell.isHighlight = false
        }
        requestLayout()
    }

    /** 高亮被选中的那一块（颜色对得上的才亮）。 */
    fun setSelectedTile(paint: AzulTilePaint?) {
        cells.forEachIndexed { index, cell ->
            cell.isHighlight = paint != null && bound.getOrNull(index) == paint
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        setMeasuredDimension(size, size)

        // 2×2 的方块要塞进圆里：内接正方形的边长只有盘径的 0.707，
        // 按 0.72 算，四块砖的角刚好不越过盘沿
        val block = (size * BLOCK_RATIO).toInt()
        val gap = (size * GAP_RATIO).toInt()
        tileSize = ((block - gap) / COLUMNS).coerceAtLeast(1)

        val spec = MeasureSpec.makeMeasureSpec(tileSize, MeasureSpec.EXACTLY)
        val corner = tileSize * AzulTileCell.CELL_CORNER_RATIO
        cells.forEach {
            if (it.isVisible) it.view.measure(spec, spec)
            it.cornerRadius = corner
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = (width * GAP_RATIO).toInt()
        val block = tileSize * COLUMNS + gap
        // 2×2 的方块居中；砖的位置固定，所以少一块时那个位置就空着
        val startX = (width - block) / 2
        val startY = (height - block) / 2

        cells.forEachIndexed { index, cell ->
            if (!cell.isVisible) return@forEachIndexed
            val left = startX + (index % COLUMNS) * (tileSize + gap)
            val top = startY + (index / COLUMNS) * (tileSize + gap)
            cell.view.layout(left, top, left + tileSize, top + tileSize)
        }
    }

    companion object {
        /** 盘上最多四块砖（引擎一盘最多发 4 块）。 */
        private const val MAX_TILES = 4

        /** 2×2 */
        private const val COLUMNS = 2

        /** 2×2 方块占盘径的比例（内接正方形 = 0.707，留一点余量）。 */
        private const val BLOCK_RATIO = 0.72f

        /** 砖缝，按盘径比例走，盘子缩小时不会崩。 */
        private const val GAP_RATIO = 0.04f
    }
}
