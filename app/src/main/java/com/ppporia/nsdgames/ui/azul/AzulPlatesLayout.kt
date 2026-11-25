package com.ppporia.nsdgames.ui.azul

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.poria.base.ext.dp2px

/**
 * 工厂盘队列：**瀑布布局**（一行摆不下就换行），盘子是正圆、尺寸自适应。
 *
 * 算法很土但很对：把"分成几列"从 1 试到 n，每种分法算一下格子边长，
 * 取**边长最大**的那种。9 个盘子在 378×340 里会自己选 3 列 × 3 行，
 * 而横屏那种"很宽很矮"的区域会自己选 5 列 × 2 行——不用针对方向写两套。
 *
 * 被拿走的盘子只把 View 藏起来、**不重排**：盘子位置固定，手不会因为上一手少了两个盘子就点错。
 *
 * 两步选砖的第一步就发生在这里：点盘里的某一块砖（盘子本身不用点）。
 */
class AzulPlatesLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    private val gap = context.dp2px(GAP_DP)
    private val plates = ArrayList<AzulPlateLayout>()

    /** 一个盘子（外接正方形）的边长，测量后才有值。 */
    var plateSize: Int = 0
        private set

    /** 点某一盘里的某一块砖：`(盘号, 那块砖的 paint)`。 */
    var onTileClick: ((plateIndex: Int, paint: AzulTilePaint) -> Unit)? = null
        set(value) {
            field = value
            applyTileClick()
        }

    private var columns = 1
    private var rows = 1

    fun bind(list: List<List<AzulTilePaint>>) {
        while (plates.size < list.size) {
            val plate = AzulPlateLayout(context)
            plates.add(plate)
            addView(plate)
        }
        plates.forEachIndexed { index, plate ->
            val tiles = list.getOrNull(index)
            plate.visibility = if (tiles == null) View.GONE else View.VISIBLE
            if (tiles != null) {
                plate.bind(tiles)
                plate.setSelectedTile(null)
            }
        }
        applyTileClick()
        requestLayout()
    }

    /** 高亮"被选中的那一盘里的那一块"，其他盘子恢复原样。 */
    fun setSelected(plateIndex: Int?, paint: AzulTilePaint?) {
        plates.forEachIndexed { index, plate ->
            plate.setSelectedTile(if (index == plateIndex) paint else null)
        }
    }

    private fun applyTileClick() {
        val callback = onTileClick
        plates.forEachIndexed { index, plate ->
            plate.onTileClick = callback?.let { cb ->
                { paint: AzulTilePaint -> cb.invoke(index, paint) }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        val visible = visiblePlates()
        if (visible.isEmpty() || width <= 0 || height <= 0) {
            plateSize = 0
            return
        }

        var bestColumns = 1
        var bestCell = 0
        for (c in 1..visible.size) {
            val r = (visible.size + c - 1) / c
            val cellWidth = (width - gap * (c - 1)) / c
            val cellHeight = (height - gap * (r - 1)) / r
            val cell = minOf(cellWidth, cellHeight)
            if (cell > bestCell) {
                bestCell = cell
                bestColumns = c
            }
        }

        columns = bestColumns
        rows = (visible.size + bestColumns - 1) / bestColumns
        plateSize = bestCell

        val spec = MeasureSpec.makeMeasureSpec(plateSize, MeasureSpec.EXACTLY)
        visible.forEach { it.measure(spec, spec) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val visible = visiblePlates()
        if (visible.isEmpty() || plateSize <= 0) return

        val totalWidth = columns * plateSize + (columns - 1) * gap
        val totalHeight = rows * plateSize + (rows - 1) * gap
        val startX = (width - totalWidth) / 2
        val startY = (height - totalHeight) / 2

        visible.forEachIndexed { index, plate ->
            val left = startX + (index % columns) * (plateSize + gap)
            val top = startY + (index / columns) * (plateSize + gap)
            plate.layout(left, top, left + plateSize, top + plateSize)
        }
    }

    private fun visiblePlates(): List<AzulPlateLayout> =
        plates.filter { it.visibility == View.VISIBLE }

    companion object {
        private const val GAP_DP = 6
    }
}
