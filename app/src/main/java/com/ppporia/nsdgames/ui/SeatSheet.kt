package com.ppporia.nsdgames.ui

import android.app.Activity
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.poria.netnsd.room.RoomSnapshot
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.SheetSeatBinding

/**
 * 座位面板：点座位条上的一个座位弹出来。
 *
 * 里面只放"这个座位现在能做的动作"，动作本身只是回调到会话层发命令
 * （最终由服务端判定并广播新快照，这里不改任何状态）：
 *
 * - 空位：我坐这里 / 房主把某个人挪过来
 * - 有人：和我换座位（房主）/ 请他离开（房主）
 */
fun showSeatSheet(
    activity: Activity,
    seat: Int,
    snapshot: RoomSnapshot,
    myId: String,
    canArrange: Boolean,
    onTakeSeat: (Int) -> Unit,
    onSwap: (Int, Int) -> Unit,
    onKick: (String) -> Unit,
) {
    val dialog = BottomSheetDialog(activity)
    val binding = SheetSeatBinding.inflate(LayoutInflater.from(activity))
    dialog.setContentView(binding.root)

    val occupant = snapshot.playerAt(seat)
    val me = snapshot.player(myId)
    val actions = mutableListOf<Pair<String, () -> Unit>>()

    if (occupant == null) {
        binding.textSeatTitle.text = activity.getString(R.string.seat_title_empty, seat + 1)
        binding.textSeatDesc.text = activity.getString(
            if (canArrange) R.string.seat_desc_arrange else R.string.seat_desc_take,
        )
        if (me != null && me.seat != seat) {
            actions += activity.getString(R.string.seat_sit) to { onTakeSeat(seat) }
        }
        if (canArrange) {
            snapshot.players.filter { it.id != myId }.forEach { player ->
                actions += activity.getString(R.string.seat_move_here, player.name) to {
                    onSwap(player.seat, seat)
                }
            }
        }
    } else {
        binding.textSeatTitle.text = activity.getString(R.string.seat_title_occupied, seat + 1, occupant.name)
        binding.textSeatDesc.text = activity.getString(
            if (canArrange) R.string.seat_desc_arrange else R.string.seat_desc_view,
        )
        if (canArrange) {
            if (me != null && me.seat != seat) {
                actions += activity.getString(R.string.seat_swap_me) to { onSwap(me.seat, seat) }
            }
            if (!occupant.host) {
                actions += activity.getString(R.string.seat_kick, occupant.name) to { onKick(occupant.id) }
            }
        }
    }

    actions.forEach { (label, action) ->
        val button = LayoutInflater.from(activity)
            .inflate(R.layout.item_sheet_action, binding.containerSeatActions, false) as MaterialButton
        button.text = label
        button.setOnClickListener {
            action()
            dialog.dismiss()
        }
        binding.containerSeatActions.addView(button)
    }

    dialog.show()
}
