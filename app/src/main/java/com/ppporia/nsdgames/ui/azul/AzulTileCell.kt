package com.ppporia.nsdgames.ui.azul

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.ppporia.nsdgames.databinding.ItemAzulTileCellBinding

/**
 * 一个格子的封装（**已经 inflate 好的 View**，自己不会加进父容器）。
 *
 * 对局页里三种"砖"都是它：盘子里的砖、墙面/预备区的格子、惩罚区的格子。
 * 它只管"长什么样"，不管"摆在哪"——位置由 [AzulGridLayout] / [AzulPlatesLayout] 这些容器决定。
 *
 * 颜色走 [MaterialCardView.setCardBackgroundColor]（`backgroundTintList` 改不动 card 的底层 drawable，
 * 之前那版其实是没生效的）。
 */
class AzulTileCell(parent: ViewGroup) {
    private val binding = ItemAzulTileCellBinding.inflate(
        LayoutInflater.from(parent.context),
        parent, false
    )

    val view: ViewGroup get() = binding.root

    /** 不可见但仍然占位（预备区那个三角形要靠它保持形状）。 */
    var isVisible: Boolean
        set(value) { binding.root.isVisible = value }
        get() = binding.root.isVisible

    private var cardColor: Int = 0

    /** 格子底色，也就是"这块砖是什么颜色"。值没变就不碰 card（一轮要刷 250 个格子）。 */
    var backgroundColor: Int
        set(value) {
            if (cardColor == value) return
            cardColor = value
            binding.root.setCardBackgroundColor(value)
        }
        get() = cardColor

    /** 盖子上的高亮环（交互接上之后用）。 */
    var isHighlight: Boolean
        set(value) { binding.itemAzulTitleCellHighlight.isVisible = value }
        get() = binding.itemAzulTitleCellHighlight.isVisible

    /**
     * 圆角（px）。**必须按边长给**：同一个格子在"我的棋盘"上 32dp、在对手区只有 9dp，
     * 固定 4dp 会让小格子变成圆点。容器量出边长后调一下（见 `CELL_CORNER_RATIO`）。
     */
    var cornerRadius: Float
        set(value) {
            if (binding.root.radius != value) binding.root.radius = value
        }
        get() = binding.root.radius

    /** 盖在砖上的小字：先手砖的「先」、惩罚区的「−1」、溢出的「+3」。空串 = 不显示。 */
    var text: String?
        set(value) {
            if (value?.isNotEmpty() == true) {
                binding.itemAzulTitleCellText.text = value
                binding.itemAzulTitleCellText.isVisible = true
            } else binding.itemAzulTitleCellText.isVisible = false
        }
        get() = binding.itemAzulTitleCellText.text.toString()

    /** 花纹层（`builtin:azul.*` 纹理到了之后往这里喂图；null = 纯色块）。 */
    var bitmap: Bitmap? = null
        set(value) {
            if (field === value) return
            binding.itemAzulTitleCellImage.setImageBitmap(value)
            binding.itemAzulTitleCellImage.isVisible = value != null
            field = value
        }

    companion object {
        /** 圆角 / 边长。0.18 时 32dp 的砖是 6dp 圆角、9dp 的迷你砖是 1.6dp，两边看起来是同一个东西。 */
        const val CELL_CORNER_RATIO = 0.18f
    }
}
