package com.ppporia.nsdgames.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.poria.netnsd.chat.ChatMessage
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ItemMessageBinding

/**
 * 聊天记录。自己的消息靠右、不显示昵称；别人的靠左、带昵称。
 *
 * `areItemsTheSame` 用**引用相等**：每条消息都是新对象，内容相同的两条（比如连发两个"好"）
 * 也必须是两个 item，否则 DiffUtil 会把它们当成同一个。
 */
class MessageAdapter : ListAdapter<ChatMessage, MessageAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            binding.root.gravity = if (message.mine) Gravity.END else Gravity.START
            binding.bubble.setBackgroundResource(
                if (message.mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other
            )
            binding.textSender.isVisible = !message.mine
            binding.textSender.text = message.sender
            binding.textMessage.text = message.text
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem === newItem

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem == newItem
    }
}
