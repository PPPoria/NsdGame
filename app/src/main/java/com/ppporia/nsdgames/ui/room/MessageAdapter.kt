package com.ppporia.nsdgames.ui.room

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.poria.netnsd.lobby.LobbySnapshot
import com.poria.netnsd.profile.Profile
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ItemMessageBinding

/** 聊天列表里的一行（UI 自己的模型，不碰协议）。 */
data class RoomMessageItem(
    val seq: Int,
    val sender: String,
    val text: String,
    val mine: Boolean,
)

/**
 * 快照 → 消息列表。
 *
 * 消息里只有 `playerId`（房间快照不装名字），昵称去 `profile` 频道给的资料表里查；
 * 资料还没到就先显示占位。
 */
fun messagesOf(
    snapshot: LobbySnapshot?,
    profiles: Map<String, Profile>,
    myId: String,
): List<RoomMessageItem> {
    val messages = snapshot?.messages ?: return emptyList()
    return messages.map { message ->
        RoomMessageItem(
            seq = message.seq,
            sender = profiles[message.playerId]?.name?.ifEmpty { null } ?: "…",
            text = message.text,
            mine = message.playerId == myId,
        )
    }
}

/**
 * 聊天记录。自己的消息靠右、不显示昵称；别人的靠左、带昵称。
 *
 * `areItemsTheSame` 按服务端给的 `seq`：内容相同的两条（比如连发两个"好"）也是两条。
 */
class MessageAdapter : ListAdapter<RoomMessageItem, MessageAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: RoomMessageItem) {
            binding.root.gravity = if (message.mine) Gravity.END else Gravity.START
            binding.bubble.setBackgroundResource(
                if (message.mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other
            )
            binding.textSender.isVisible = !message.mine
            binding.textSender.text = message.sender
            binding.textMessage.text = message.text
        }
    }

    private object Diff : DiffUtil.ItemCallback<RoomMessageItem>() {
        override fun areItemsTheSame(oldItem: RoomMessageItem, newItem: RoomMessageItem): Boolean =
            oldItem.seq == newItem.seq

        override fun areContentsTheSame(oldItem: RoomMessageItem, newItem: RoomMessageItem): Boolean =
            oldItem == newItem
    }
}
