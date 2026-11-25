package com.ppporia.nsdgames.ui.azul

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.color.MaterialColors
import com.poria.base.R as BaseR
import com.poria.base.ext.dp2px
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.AzulWorkspaceBinding

/**
 * 一个玩家的工作区（你说的 WorkspaceLayout）：
 *
 * ```
 * ┌──────────┬──────────┐
 * │ 预备区   │  墙面    │  ← 两个 AzulGridLayout 各占一半宽
 * ├──────────┴──────────┤
 * │ ◀ ▪▪▪▪▪▪▪▪▪▪… −5   │  ← 惩罚区 14 格横向滚动 + 右边"这一区一共扣多少"
 * └─────────────────────┘
 * ```
 *
 * 结构就是 [R.layout.azul_workspace]，这个类只做三件事：
 * 1. 把 [AzulTilePaint] 涂到格子上（**不认识 Tile，也不认识任何具体颜色**：那是 Activity 的表）；
 * 2. 按宽度把惩罚区的砖调到和预备区一样大（[applyPunishmentTile]）；
 * 3. 惩罚区右边写一个非正数，表示这一区当前会扣多少分。
 *
 * **惩罚区是"固定 14 格 + 横向滚动"**，不是"算能放几格再动态加"：
 * 格数跟宽度解耦，就没有"宽度还没量出来 → 一格都没加 → 整块消失"这种失效路径；
 * 14 也是引擎 `AzulPunishment.punishment` 的上限，滑一下就知道后面还有几档扣分。
 *
 * 砖的大小不用外面传：预备区拿一半宽度算出来，惩罚区跟着它走，
 * 所以整块永远是 10:6，缩到对手区 1/3 宽度也不会散架。
 */
class AzulWorkspaceLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: AzulWorkspaceBinding

    private val gap = context.dp2px(GAP_DP)

    /** 惩罚区的 14 个格子：**初始化就建好，只改尺寸**，绝不因为宽度问题少建。 */
    private val punishmentCells = ArrayList<AzulTileCell>(MAX_PUNISHMENT)

    private var floorRows: List<Pair<AzulTilePaint, Int>?> = emptyList()
    private var wallCells: List<List<AzulTilePaint>> = emptyList()
    private var punishmentTiles: List<AzulTilePaint> = emptyList()
    private var emptyPaint: AzulTilePaint = AzulTilePaint(0x26000000)

    /** 当前惩罚区一格的边长；-1 = 还没定过。 */
    private var punishmentTile = -1

    /** 一格的边长（px）。公共区拿它对齐"场上砖和我的一样大"；测量后才有值。 */
    val tileSize: Int get() = binding.azulWorkspaceFloor.cellSize

    /**
     * 小号排版（其他玩家）：惩罚区里**不印格子上的 −1/−2**，只保留右边那个合计。
     * 小格子上印数字既看不清也吵。
     */
    var compact: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                applyCompact()
                paintPunishment()
            }
        }

    init {
        orientation = VERTICAL
        binding = AzulWorkspaceBinding.inflate(LayoutInflater.from(context), this)

        binding.azulWorkspaceFloor.gap = gap
        binding.azulWorkspaceWall.gap = gap
        // 预备区只有三角形上的那 15 格亮着（INVISIBLE：仍然占位，形状不会塌）
        binding.azulWorkspaceFloor.setVisibleOnly(AzulGridLayout.floorPos)

        repeat(MAX_PUNISHMENT) {
            val cell = AzulTileCell(binding.azulWorkspacePunishSlots)
            punishmentCells.add(cell)
            binding.azulWorkspacePunishSlots.addView(cell.view)
        }
        applyCompact()
    }

    // ── 数据 ────────────────────────────────────────────────────────────────

    /**
     * @param floor `AzulFloor.rows` 换成 paint 之后：第 i 行 (砖, 个数)，第 i 行最多 i+1 个
     * @param wall `AzulWall.grid[row][col]` 换成 paint 之后；**每格都要给**（空位的淡色也由调用方算好）
     * @param punishment `AzulPunishment.tiles` 换成 paint 之后
     * @param empty 空位的 paint。**必须给**：空位要能"擦回原样"，
     *              否则一行砖收走后那一行还留着上一手的颜色
     */
    fun bind(
        floor: List<Pair<AzulTilePaint, Int>?>,
        wall: List<List<AzulTilePaint>>,
        punishment: List<AzulTilePaint>,
        empty: AzulTilePaint,
    ) {
        floorRows = floor
        wallCells = wall
        punishmentTiles = punishment
        emptyPaint = empty
        paintFloor()
        paintWall()
        paintPunishment()
    }

    /** 高亮预备区某一行（交互接上之后用：点一行 → 亮一行）。 */
    fun highlightFloor(row: Int, on: Boolean) {
        val pos = (0..4).map { col -> col to row }
        binding.azulWorkspaceFloor.setHighlightOnly(if (on) pos else emptyList())
    }

    // ── 交互 ────────────────────────────────────────────────────────────────

    /**
     * 点预备区某一行。**只有设了它，格子才能点**：其他玩家的盘不设，就点不动。
     * 传进来的是行号（0..4），合法性由对局页判（这里只管"点到哪一行"）。
     */
    var onFloorRowClick: ((row: Int) -> Unit)? = null
        set(value) {
            field = value
            binding.azulWorkspaceFloor.onCellClick = value?.let { callback ->
                { _, row -> callback.invoke(row) }
            }
        }

    /**
     * 标出"能放的行"：能放的高亮，其余的压暗（**不隐藏**——找不到行比点错更糟）。
     * 传空表 = 全部清掉。
     */
    fun setRowTargets(rows: List<Int>) {
        val highlighted = ArrayList<Pair<Int, Int>>()
        for (row in 0..4) {
            val cells = (0 until row + 1).map { i -> (4 - row + i) to row }
            val isTarget = rows.contains(row)
            if (isTarget) highlighted.addAll(cells)
            binding.azulWorkspaceFloor.setDimmed(cells, rows.isNotEmpty() && !isTarget)
        }
        binding.azulWorkspaceFloor.setHighlightOnly(highlighted)
    }

    // ── 测量：两块棋盘等宽，余数进中间那列三角形 ──────────────────────────────

    /** 一块棋盘的宽度（左墙各一份，严格相等）。 */
    private var gridWidth = 0

    /** 中间那列三角形的宽度：名义宽度 + 奇数宽度多出来的 0/1px。 */
    private var arrowWidth = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        applyBoardWidths(width)

        // 一格多宽：和 AzulGridLayout 同一个公式、同一个 gridWidth，天然一致
        val cell = AzulGridLayout.cellSizeFor(gridWidth, gap)
        applyPunishmentTile(cell)
        // 三角形在测量前就拿到尺寸，第一帧就对齐（不用等一次回填）
        binding.azulWorkspaceArrow.setMetrics(cell, gap)

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * 分宽度：两个棋盘**等宽**，中间那列吃掉 `总宽 - 2 × 棋盘宽`（0 或 1px）。
     *
     * 这正是 [AzulArrowLayout] 存在的顺手好处：肉眼看到的是"中间那列有个方向箭头"，
     * 实际上它还兜住了奇数宽度，两块棋盘永远一样大。
     */
    private fun applyBoardWidths(totalWidth: Int) {
        if (totalWidth <= 0) return
        val nominal = (totalWidth * ARROW_RATIO).toInt()
            .coerceIn(context.dp2px(ARROW_MIN_DP), context.dp2px(ARROW_MAX_DP))
            .coerceAtLeast(1)
        val grid = ((totalWidth - nominal) / 2).coerceAtLeast(1)
        val arrow = (totalWidth - grid * 2).coerceAtLeast(0)
        if (grid == gridWidth && arrow == arrowWidth) return

        gridWidth = grid
        arrowWidth = arrow
        binding.azulWorkspaceFloor.layoutParams =
            LayoutParams(grid, LayoutParams.WRAP_CONTENT)
        binding.azulWorkspaceArrow.layoutParams =
            LayoutParams(arrow, LayoutParams.WRAP_CONTENT)
        binding.azulWorkspaceWall.layoutParams =
            LayoutParams(grid, LayoutParams.WRAP_CONTENT)
    }

    private fun applyPunishmentTile(tile: Int) {
        if (tile <= 0 || tile == punishmentTile) return
        punishmentTile = tile
        val corner = tile * AzulTileCell.CELL_CORNER_RATIO
        punishmentCells.forEach { cell ->
            cell.view.layoutParams = LayoutParams(tile, tile).apply { marginEnd = gap }
            cell.cornerRadius = corner
        }
    }

    // ── 画 ──────────────────────────────────────────────────────────────────

    /** 预备区：第 row 行占第 (4-row)..4 列，砖**从右往左**填（线下是贴着墙面那侧先满）。 */
    private fun paintFloor() {
        val grid = binding.azulWorkspaceFloor
        for (row in 0 until 5) {
            val entry = floorRows.getOrNull(row)
            val rowPaint = entry?.first
            val length = row + 1
            val quantity = (entry?.second ?: 0).coerceIn(0, length)
            for (i in 0 until length) {
                val col = 4 - row + i
                val paint = if (rowPaint != null && i >= length - quantity) rowPaint else emptyPaint
                grid.paintAt(col, row, paint)
            }
        }
    }

    private fun paintWall() {
        val grid = binding.azulWorkspaceWall
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                grid.paintAt(col, row, wallCells.getOrNull(row)?.getOrNull(col) ?: emptyPaint)
            }
        }
    }

    /**
     * 惩罚区：14 格全部摆下（横向滚动），只有前面几格有砖。
     * 空位印自己的分值（−1 −1 −2 −2 −2 −3 …），[compact] 时不印。
     */
    private fun paintPunishment() {
        punishmentCells.forEachIndexed { index, cell ->
            val paint = punishmentTiles.getOrNull(index)
            if (paint != null) {
                cell.backgroundColor = paint.color
                cell.bitmap = paint.bitmap
                cell.text = paint.label
            } else {
                cell.backgroundColor = emptyPaint.color
                cell.bitmap = emptyPaint.bitmap
                cell.text = if (compact) null else
                    context.getString(R.string.azul_penalty, -AzulTiles.penaltyAt(index))
            }
        }
        paintPenaltyTotal()
    }

    /** 右边那个"这一区一共扣多少"：非正数（0 = 还没掉砖）。 */
    private fun paintPenaltyTotal() {
        val penalty = AzulTiles.penaltyOf(punishmentTiles.size)
        val label = binding.azulWorkspacePenalty
        label.text = if (penalty == 0) {
            context.getString(R.string.azul_penalty_zero)
        } else {
            context.getString(R.string.azul_penalty, -penalty)
        }
        label.setTextColor(
            MaterialColors.getColor(
                label,
                if (penalty == 0) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary,
            )
        )
    }

    private fun applyCompact() {
        binding.azulWorkspacePenalty.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(if (compact) BaseR.dimen.text_caption else BaseR.dimen.text_label)
        )
    }

    companion object {
        /** 惩罚区一共 14 格：引擎 `AzulPunishment.punishment` 那张表就是 14 个。 */
        const val MAX_PUNISHMENT = 14

        private const val GAP_DP = 2

        /** 中间那列三角形占整宽的比例，再夹在 [ARROW_MIN_DP] ~ [ARROW_MAX_DP] 之间。 */
        private const val ARROW_RATIO = 0.03f
        private const val ARROW_MIN_DP = 4
        private const val ARROW_MAX_DP = 12
    }
}
