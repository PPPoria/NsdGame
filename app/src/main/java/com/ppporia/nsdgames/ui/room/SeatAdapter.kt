package com.ppporia.nsdgames.ui.room

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.poria.base.ext.setOnDebouncedClickListener
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ItemSeatBinding

/** 一个座位的渲染数据。座位号就是出手顺序，空位也要画出来。 */
data class SeatInfo(
    val seat: Int,
    val name: String,
    val tag: String,
    val occupied: Boolean,
    val mine: Boolean,
    /** 已经解好的头像；null = 没设头像或者图还没到（先不画）。 */
    val avatar: Bitmap? = null,
)

/**
 * 座位条。点一下交给 [RoomActivity] 弹座位面板（调座次 / 入座 / 请人离开都在那儿）。
 *
 * 这里只画：画什么完全由服务端快照决定（列表内容来自 `RoomState.snapshot`）。
 */
class SeatAdapter(private val onClick: (Int) -> Unit) :
    ListAdapter<SeatInfo, SeatAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSeatBinding.inflate(LayoutInflater.from(parent.context), parent, false), onClick)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(
        private val binding: ItemSeatBinding,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var seat = -1

        init {
            binding.cardSeat.setOnDebouncedClickListener(400) {
                if (seat >= 0) onClick(seat)
            }
        }

        fun bind(item: SeatInfo) {
            seat = item.seat
            val context = binding.root.context
            binding.textSeatNo.text = context.getString(R.string.room_seat_no, item.seat + 1)
            binding.textSeatName.text = item.name
            binding.textSeatTag.isVisible = item.tag.isNotEmpty()
            binding.textSeatTag.text = item.tag
            binding.cardSeatAvatar.isVisible = item.avatar != null
            binding.imageSeatAvatar.setImageBitmap(item.avatar)

            // 配色来自主题（app 自己的 attr，见 values/attrs.xml）：自己的座位用主题色描边，
            // 有人坐的座位底色重一点、空位轻一点，一眼能分清
            binding.cardSeat.strokeColor = MaterialColors.getColor(
                binding.cardSeat,
                if (item.mine) R.attr.roomSeatStrokeMine else R.attr.roomSeatStroke,
            )
            binding.cardSeat.strokeWidth = if (item.mine) 2 else 1
            binding.cardSeat.setCardBackgroundColor(
                MaterialColors.getColor(
                    binding.cardSeat,
                    if (item.occupied) R.attr.roomSeatSurfaceOccupied else R.attr.roomSeatSurfaceEmpty,
                )
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<SeatInfo>() {
        override fun areItemsTheSame(oldItem: SeatInfo, newItem: SeatInfo): Boolean =
            oldItem.seat == newItem.seat

        override fun areContentsTheSame(oldItem: SeatInfo, newItem: SeatInfo): Boolean =
            oldItem == newItem
    }
}
