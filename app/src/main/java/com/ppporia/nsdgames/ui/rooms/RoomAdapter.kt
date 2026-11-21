package com.ppporia.nsdgames.ui.rooms

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.poria.base.ext.setOnDebouncedClickListener
import com.poria.netnsd.FoundHost
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ItemRoomBinding

/**
 * 搜到的房间列表。一行 = 一个 [FoundHost]（服务名 + IP:端口）。
 *
 * 点一下就进房；`setOnDebouncedClickListener` 挡住连点导致进出两个房间。
 */
class RoomAdapter(private val onClick: (FoundHost) -> Unit) :
    ListAdapter<FoundHost, RoomAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false), onClick)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(
        private val binding: ItemRoomBinding,
        private val onClick: (FoundHost) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var room: FoundHost? = null

        init {
            binding.root.setOnDebouncedClickListener(800) {
                room?.let(onClick)
            }
        }

        fun bind(item: FoundHost) {
            room = item
            binding.textRoomName.text = item.serviceName
            binding.textRoomHost.text = binding.root.context.getString(
                R.string.room_item_subtitle,
                "${item.host}:${item.port}",
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<FoundHost>() {
        // NSD 的服务名在同一局域网里唯一，拿它当 key
        override fun areItemsTheSame(oldItem: FoundHost, newItem: FoundHost): Boolean =
            oldItem.serviceName == newItem.serviceName

        override fun areContentsTheSame(oldItem: FoundHost, newItem: FoundHost): Boolean =
            oldItem == newItem
    }
}
