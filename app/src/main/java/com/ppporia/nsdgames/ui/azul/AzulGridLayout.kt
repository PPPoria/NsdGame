package com.ppporia.nsdgames.ui.azul

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.poria.base.ext.dp2px

/**
 * 5×5 的格子阵：**预备区**（用 [floorPos] 亮出那个三角形）和**墙面**（整 25 格）共用一个组件。
 *
 * 坐标约定：**`Pair(列, 行)`**，和 [onLayout] 里的 `i → left, j → top` 对得上；
 * 所以 `floorPos` 是"第 i 列从第 4-i 行往下一路到第 4 行"，也就是线下那块右边对齐的三角。
 *
 * **边长只认宽度**：它是"10 列棋盘的一半"，高度跟着宽度走。
 * 如果按 `min(宽, 高)` 走，放进一个高度紧张的容器里就会被压成半宽的小方块
 * （工作区那两块是靠 `layout_weight` 各占一半宽度的，高度必须由宽度决定）。
 *
 * 空格子用 `INVISIBLE` 而不是 `GONE`——那条三角形靠的正是"看不见但仍然占位"。
 */
class AzulGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val span = SPAN

    /** 格子之间的空隙（含外圈）。改它会重新测量。工作区会把它调小（2dp），小格子才放得下。 */
    var gap: Int = context.dp2px(6)
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** 测量后的一格边长；工作区拿它定惩罚区的格子大小。 */
    var cellSize: Int = 0
        private set

    /**
     * 点某一格。**只有设置了它，格子才可点**：其他玩家的盘不该能点，
     * 所以那边保持 null，连监听的挂不上去。
     */
    var onCellClick: ((col: Int, row: Int) -> Unit)? = null
        set(value) {
            field = value
            applyClickable()
        }

    private val tileCells: List<List<AzulTileCell>> = List(span) {
        List(span) {
            val cell = AzulTileCell(this)
            addView(cell.view)
            cell
        }
    }

    private fun applyClickable() {
        val callback = onCellClick
        tileCells.forEachIndexed { col, column ->
            column.forEachIndexed { row, cell ->
                cell.view.setOnClickListener(
                    if (callback == null) null
                    else View.OnClickListener { callback.invoke(col, row) }
                )
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = when (MeasureSpec.getMode(widthMeasureSpec)) {
            // 宽度没定（UNSPECIFIED）才退化成"取宽高里小的"
            MeasureSpec.UNSPECIFIED -> minOf(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec)
            )
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        setMeasuredDimension(side, side)

        cellSize = cellSizeOf(side)
        val spec = MeasureSpec.makeMeasureSpec(cellSize, MeasureSpec.EXACTLY)
        val corner = cellSize * AzulTileCell.CELL_CORNER_RATIO
        for (i in 0 until childCount) {
            getChildAt(i).measure(spec, spec)
        }
        tileCells.forEach { row -> row.forEach { it.cornerRadius = corner } }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cellSize = cellSizeOf(width)
        for (i in 0 until span) {
            for (j in 0 until span) {
                val left = (gap + i * (cellSize + gap))
                val top = (gap + j * (cellSize + gap))
                tileCells[i][j].view.layout(left, top, left + cellSize, top + cellSize)
            }
        }
    }

    /** 给定边长时一格多大。工作区算"砖高"时用的是同一个公式。 */
    private fun cellSizeOf(size: Int): Int = cellSizeFor(size, gap)

    fun setVisibleOnly(pos: List<Pair<Int, Int>>) {
        tileCells.forEach { r -> r.forEach { it.isVisible = false } }
        pos.forEach { (x, y) ->
            tileCells[x][y].isVisible = true
        }
    }

    fun setHighlightOnly(pos: List<Pair<Int, Int>>) {
        tileCells.forEach { r -> r.forEach { it.isHighlight = false } }
        pos.forEach { (x, y) ->
            tileCells[x][y].isHighlight = true
        }
    }

    /** 压暗/恢复：选好来源之后，放不进去的行要退到背景里（但不隐藏，不然找不到行）。 */
    fun setDimmed(pos: List<Pair<Int, Int>>, dimmed: Boolean) {
        pos.forEach { (x, y) ->
            tileCells[x][y].view.alpha = if (dimmed) DIM_ALPHA else 1f
        }
    }

    /** 涂一格。pos 约定同 [floorPos]：**先列后行**。 */
    fun paintAt(col: Int, row: Int, paint: AzulTilePaint) {
        tileCells[col][row].apply {
            backgroundColor = paint.color
            bitmap = paint.bitmap
        }
    }

    companion object {
        /** 横竖都是 5 格。 */
        const val SPAN = 5

        /** 不可选的行压到这个透明度：还看得见，但明显不是目标。 */
        private const val DIM_ALPHA = 0.35f

        /**
         * 给定边长和一格的间隙时，一格多大。
         *
         * **工作区也算这一份**：两块棋盘严格等宽之后，中间那列三角形和惩罚区的砖都从同一个
         * `gridWidth` 推出来，公式只此一处，不会出现"两块各自算出不同砖高"的漂移。
         */
        fun cellSizeFor(side: Int, gap: Int): Int =
            ((side - gap * (SPAN + 1)) / SPAN).coerceAtLeast(0)

        /**
         * 预备区：第 i 列有 i+1 格，从第 4 行往上长到第 4-i 行。
         * 也就是说**第 0 行只有 1 格（最右），第 4 行有 5 格**——和 `AzulFloor.maxQuantity` 一致。
         */
        val floorPos = ArrayList<Pair<Int, Int>>().apply {
            for (i in 0..4) for (j in 4 downTo 4 - i) add(i to j)
        }.toList()

        /** 墙面：25 格全亮。 */
        val wallPos = List(5) { i ->
            List(5) { j -> i to j }
        }
    }
}
