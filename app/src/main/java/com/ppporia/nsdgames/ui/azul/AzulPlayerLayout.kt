package com.ppporia.nsdgames.ui.azul

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.poria.base.R as BaseR
import com.poria.base.ext.dp2px
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.AzulPlayerBinding

/**
 * 一个玩家：上面一行「头像 + 昵称 + 分数 + 轮次点」，下面他的 [AzulWorkspaceLayout]。
 *
 * **同一个类用在两处**（就是你说的 PlayerLayout）：
 * - 其他玩家：横向列表里，宽度是 1/3 屏，用 [bind] 的 `compact = true`（小头像、小字）；
 * - 我自己：宽度整屏、`compact = false`。
 *
 * 工作区的砖大小不是这里定的：宽度进来多少，它自己算。所以"其他玩家那三块"和
 * "我自己这块"用一份布局、一份绘制代码，不会出现两套画法慢慢漂移。
 */
class AzulPlayerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: AzulPlayerBinding

    /** 他的工作区（棋盘）。 */
    val workspace: AzulWorkspaceLayout

    init {
        orientation = VERTICAL
        binding = AzulPlayerBinding.inflate(LayoutInflater.from(context), this)
        workspace = binding.azulPlayerWorkspace
    }

    /**
     * @param act 现在该他动（只是提示：真正的校验在服务器）
     * @param compact 其他玩家的小号排版
     */
    fun bind(
        name: String,
        score: Int,
        avatar: Bitmap?,
        act: Boolean,
        compact: Boolean = false,
    ) {
        // 字号唯一来源是 :base 的 dimens（app 的 R 里没有它们：nonTransitiveRClass）
        val caption = resources.getDimension(BaseR.dimen.text_caption)
        val label = resources.getDimension(BaseR.dimen.text_label)
        val subtitle = resources.getDimension(BaseR.dimen.text_subtitle)

        val avatarSize = context.dp2px(if (compact) 20 else 40)
        binding.azulPlayerAvatarBox.layoutParams = binding.azulPlayerAvatarBox.layoutParams.apply {
            width = avatarSize
            height = avatarSize
        }
        binding.azulPlayerAvatar.isVisible = avatar != null
        binding.azulPlayerAvatar.setImageBitmap(avatar)

        binding.azulPlayerAvatarFallback.isVisible = avatar == null
        binding.azulPlayerAvatarFallback.text = name.take(1)
        binding.azulPlayerAvatarFallback.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            if (compact) caption else label
        )

        binding.azulPlayerName.text = name
        binding.azulPlayerName.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (compact) caption else label)

        // 我的那行字号大、带「分」字；对手那行窄，只留数字
        binding.azulPlayerScore.text = if (compact) {
            context.getString(R.string.azul_score_short, score)
        } else {
            context.getString(R.string.azul_score, score)
        }
        binding.azulPlayerScore.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            if (compact) label else subtitle
        )

        binding.azulPlayerTurnDot.isVisible = act
        if (compact) {
            binding.azulPlayerTurnDot.layoutParams = binding.azulPlayerTurnDot.layoutParams.apply {
                width = context.dp2px(6)
                height = context.dp2px(6)
            }
        }
        // 小号排版：惩罚区不印格子上的 −1/−2，只留右边那个合计
        workspace.compact = compact
    }

    /** 他的局面：预备区 / 墙面 / 惩罚区（**已经上色好的 paint**，见 [AzulTilePaint]）。 */
    fun bindWorkspace(
        floor: List<Pair<AzulTilePaint, Int>?>,
        wall: List<List<AzulTilePaint>>,
        punishment: List<AzulTilePaint>,
        empty: AzulTilePaint,
    ) = workspace.bind(floor, wall, punishment, empty)
}
