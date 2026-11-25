package com.ppporia.nsdgames.ui.azul

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * 两块棋盘中间那一列**朝右的等腰三角形**（一行一个，共 5 个）。
 *
 * 这是线下棋盘上本来就有的图形：底边贴着预备区、顶点指向墙面，
 * 语义就是"这一行填满了就往右推上去"。它顺带解决了一个布局上的小麻烦——
 * 两块 5×5 各占一半宽度时，奇数宽度会多出 1px；把这个像素塞给中间这一列，
 * 左右两块就永远一样宽（[com.ppporia.nsdgames.ui.azul.AzulWorkspaceLayout] 里是这么分的）。
 *
 * 高度由 [setMetrics] 给的一格边长推出来（`5*(cell+gap)+gap`），和两块棋盘严丝合缝，
 * 所以三角形永远对着一行砖的正中间。
 */
class AzulArrowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        val base = MaterialColors.getColor(
            this@AzulArrowLayout,
            android.R.attr.textColorSecondary,
            Color.GRAY,
        )
        // 只是"方向提示"，压淡一点，别和砖抢注意力
        color = ColorUtils.setAlphaComponent(base, ALPHA)
    }

    private val path = Path()

    private var cellSize = 0
    private var gap = 0

    /** 和棋盘同一个尺寸口径：一格多大、行之间留多宽。 */
    fun setMetrics(cellSize: Int, gap: Int) {
        if (this.cellSize == cellSize && this.gap == gap) return
        this.cellSize = cellSize
        this.gap = gap
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 高度只认 metrics：和 AzulGridLayout 的 "5 格 + 6 道缝" 一模一样
        val height = if (cellSize > 0) {
            cellSize * ROWS + gap * (ROWS + 1)
        } else 0
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize <= 0 || width <= 0) return

        val centerX = width / 2f
        // 三角形只占中间那一列的 ~76% 宽，两侧留出呼吸感
        val halfWidth = width * 0.38f
        // 竖直方向占一格的 ~60%
        val halfHeight = cellSize * 0.3f

        for (row in 0 until ROWS) {
            val top = gap + row * (cellSize + gap)
            val centerY = top + cellSize / 2f
            path.reset()
            path.moveTo(centerX - halfWidth, centerY - halfHeight) // 底边：左上
            path.lineTo(centerX - halfWidth, centerY + halfHeight) // 底边：左下
            path.lineTo(centerX + halfWidth, centerY)              // 顶点：右侧
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        /** 和 [AzulGridLayout] 的 span 一致。 */
        private const val ROWS = 5

        /** 0x8C ≈ 55%：深浅主题下都能看见，又不至于抢眼。 */
        private const val ALPHA = 0x8C
    }
}
