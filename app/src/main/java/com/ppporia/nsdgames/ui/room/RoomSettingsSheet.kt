package com.ppporia.nsdgames.ui.room

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.poria.core.game.GameCatalog
import com.poria.core.game.GameInfo
import com.poria.netnsd.room.RoomSnapshot
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ItemOptionSwitchBinding
import com.ppporia.nsdgames.databinding.SheetRoomSettingsBinding

/**
 * 房间设置面板：选游戏 / 牌桌人数 / 自定义玩法。
 *
 * 面板**不存状态**：每次 [render] 都照着服务端快照重画（选中项和开关都来自快照），
 * 点一下只发命令，等新的快照推回来再重画。非房主看到的是同一样子，只是控件全灰。
 */
class RoomSettingsSheet(private val context: Context) {

    interface Listener {
        fun onSelectGame(gameId: String)
        fun onTableSize(size: Int)
        fun onOption(optionId: String, on: Boolean)
    }

    private val dialog = BottomSheetDialog(context)
    private val binding = SheetRoomSettingsBinding.inflate(LayoutInflater.from(context))
    private var optionRows: List<Pair<String, ItemOptionSwitchBinding>> = emptyList()

    var listener: Listener? = null

    var isShowing: Boolean = false
        private set

    init {
        dialog.setContentView(binding.root)
        dialog.setOnDismissListener { isShowing = false }
    }

    fun show(snapshot: RoomSnapshot?, isHost: Boolean, online: Boolean) {
        isShowing = true
        render(snapshot, isHost, online)
        dialog.show()
    }

    /** 页面销毁时收掉，避免对话框挂在一个已经没了的 Activity 上。重复调用安全。 */
    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    fun render(snapshot: RoomSnapshot?, isHost: Boolean, online: Boolean) {
        if (!isShowing) return

        binding.textSettingsHint.text = context.getString(
            if (isHost) R.string.settings_host_hint else R.string.settings_guest_hint,
        )
        val game = snapshot?.let { GameCatalog.byId(it.gameId) } ?: return
        val editable = isHost && online

        binding.textGameNote.text = context.getString(
            if (game.available) R.string.settings_game_more else R.string.settings_game_wip,
            game.title,
        )

        syncChoices(
            group = binding.groupGames,
            values = GameCatalog.games.map { it.id },
            labels = GameCatalog.games.map { it.title },
            selected = snapshot.gameId,
            editable = editable,
            onPick = { listener?.onSelectGame(it) },
        )

        val sizes = (game.minPlayers..game.maxPlayers).toList()
        syncChoices(
            group = binding.groupSize,
            values = sizes.map { it.toString() },
            labels = sizes.map { "${it} 人" },
            selected = snapshot.tableSize.toString(),
            editable = editable,
            onPick = { listener?.onTableSize(it.toIntOrNull() ?: 0) },
        )

        syncOptions(game, snapshot, editable)
    }

    private fun syncChoices(
        group: RadioGroup,
        values: List<String>,
        labels: List<String>,
        selected: String,
        editable: Boolean,
        onPick: (String) -> Unit,
    ) {
        val signature = values.joinToString("|")
        if (group.tag != signature) {
            group.removeAllViews()
            val inflater = LayoutInflater.from(context)
            values.forEachIndexed { index, value ->
                val radio = inflater.inflate(R.layout.item_choice, group, false) as RadioButton
                radio.id = View.generateViewId()
                radio.tag = value
                radio.text = labels[index]
                group.addView(radio)
            }
            group.tag = signature
        }

        // 先摘监听、再落选中态、最后挂监听：否则"同步"会被当成"用户点了"
        var selectedId = View.NO_ID
        children(group).forEach { radio ->
            radio.setOnCheckedChangeListener(null)
            radio.isEnabled = editable
            if (radio.tag == selected) selectedId = radio.id
        }
        if (selectedId == View.NO_ID) group.clearCheck() else group.check(selectedId)
        children(group).forEach { radio ->
            val value = radio.tag as? String ?: return@forEach
            radio.setOnCheckedChangeListener { _, checked -> if (checked) onPick(value) }
        }
    }

    private fun syncOptions(game: GameInfo, snapshot: RoomSnapshot, editable: Boolean) {
        val signature = game.id + "|" + game.options.joinToString(",") { it.id }
        if (binding.containerOptions.tag != signature) {
            binding.containerOptions.removeAllViews()
            val inflater = LayoutInflater.from(context)
            optionRows = game.options.map { option ->
                val row = ItemOptionSwitchBinding.inflate(inflater, binding.containerOptions, false)
                row.switchOption.text = option.title
                row.textOptionDesc.text = option.description
                binding.containerOptions.addView(row.root)
                option.id to row
            }
            binding.containerOptions.tag = signature
            binding.textNoOptions.isVisible = game.options.isEmpty()
        }

        optionRows.forEach { (id, row) ->
            row.switchOption.setOnCheckedChangeListener(null)
            row.switchOption.isChecked = snapshot.options[id] == true
            row.switchOption.isEnabled = editable
        }
        optionRows.forEach { (id, row) ->
            row.switchOption.setOnCheckedChangeListener { _, checked -> listener?.onOption(id, checked) }
        }
    }

    private fun children(group: RadioGroup): List<RadioButton> =
        (0 until group.childCount).mapNotNull { group.getChildAt(it) as? RadioButton }
}
