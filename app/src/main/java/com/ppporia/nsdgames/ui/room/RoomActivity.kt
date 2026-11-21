package com.ppporia.nsdgames.ui.room

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import com.poria.base.ext.setOnClickListenerWithClickAnimation
import com.poria.base.ui.BaseActivity
import com.poria.netnsd.room.SessionPhase
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityRoomBinding
import com.ppporia.nsdgames.utils.openRoomSession
import com.ppporia.nsdgames.viewmodel.room.RoomUiState
import com.ppporia.nsdgames.viewmodel.room.RoomVM
import com.ppporia.nsdgames.viewmodel.room.SeatTag

/**
 * 游戏准备房间页，**纯 View 层**：
 *
 * - 房间数据全在 [RoomVM] 里（而 ViewModel 那边来自服务端快照），这里
 *   `vm.state.collect { render(it) }`，不存花名册 / 座位 / 消息；
 * - 按钮只调 VM 的方法，不本地改状态——界面要等服务端推回新快照才动；
 * - 这个类只做四件事：拼字符串（i18n）、把头像 id 解成 Bitmap、弹面板、处理键盘内边距。
 *
 * 房主和客人跑的是同一个页面：开房 / 加入的差别只在 Intent，会话由 [openRoomSession] 建好后
 * 交给 [RoomVM]，页面本身完全不知道自己是哪一边。
 */
class RoomActivity : BaseActivity<ActivityRoomBinding>() {

    private val vm: RoomVM by viewModels {
        viewModelFactory { initializer { RoomVM(openRoomSession()) } }
    }

    private val seatAdapter = SeatAdapter { seat -> openSeatSheet(seat) }
    private val messageAdapter = MessageAdapter()
    private var settingsSheet: RoomSettingsSheet? = null

    /** 最近一次渲染的状态：只给"弹面板前读一眼"用，房间数据在 [vm] 里。 */
    private var uiState = RoomUiState()

    /** 头像 id → 解好的小图。同一次会话里每人最多解一次（512px WebP，解码几毫秒）。 */
    private val avatarBitmaps = HashMap<String, Bitmap>()

    private var lastMessageSeq = -1
    private var actionsBasePadding = 0

    override fun onCreateBinding(): ActivityRoomBinding =
        ActivityRoomBinding.inflate(layoutInflater)

    override fun initView() {
        actionsBasePadding = binding.rowActions.paddingBottom

        binding.recyclerSeats.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerSeats.adapter = seatAdapter

        binding.recyclerMessages.layoutManager =
            LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = messageAdapter

        settingsSheet = RoomSettingsSheet(this).apply {
            listener = object : RoomSettingsSheet.Listener {
                override fun onSelectGame(gameId: String) {
                    vm.selectGame(gameId)
                }

                override fun onTableSize(size: Int) {
                    vm.setTableSize(size)
                }

                override fun onOption(optionId: String, on: Boolean) {
                    vm.setOption(optionId, on)
                }
            }
        }
    }

    override fun initListener() {
        binding.buttonLeave.setOnClickListenerWithClickAnimation { finish() }
        binding.buttonSend.setOnClickListenerWithClickAnimation(debounceMillis = 300) { send() }
        binding.buttonSettings.setOnClickListenerWithClickAnimation { openSettings() }
        binding.buttonReady.setOnClickListenerWithClickAnimation { vm.toggleReady() }
        binding.buttonStart.setOnClickListenerWithClickAnimation { vm.start() }
    }

    override suspend fun bindUi() {
        vm.state.collect { render(it) }
    }

    /**
     * 键盘弹起时把底部顶上来：系统栏内边距 BaseActivity 已经加过了，
     * 这里只补"IME 比系统栏多出来"的那部分，补在最后一行上（整列会被顶上去）。
     */
    override fun onWindowInsetsChanged(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val extra = (ime.bottom - bars.bottom).coerceAtLeast(0)
        binding.rowActions.setPadding(
            binding.rowActions.paddingLeft,
            binding.rowActions.paddingTop,
            binding.rowActions.paddingRight,
            actionsBasePadding + extra,
        )
    }

    override fun onDestroy() {
        // 会话在 RoomVM.onCleared() 里关（它也要在主线程，而且转屏重建时不该被关）
        settingsSheet?.dismiss()
        settingsSheet = null
        super.onDestroy()
    }

    // ── 渲染（只读 UI 状态）────────────────────────────────────────────────

    private fun render(state: RoomUiState) {
        uiState = state

        binding.textTitle.text = state.title.ifBlank { getString(R.string.room_title) }
        binding.textSubtitle.text = if (state.gameKnown && state.snapshot != null) {
            getString(R.string.room_header, state.gameTitle, state.playerCount, state.tableSize)
        } else {
            getString(R.string.room_connecting_short)
        }
        binding.textStatus.text = state.note ?: when {
            state.phase == SessionPhase.CLOSED -> getString(R.string.room_status_closed)
            state.phase == SessionPhase.CONNECTING -> getString(R.string.room_status_connecting)
            state.hostless -> getString(R.string.room_status_hostless)
            else -> getString(R.string.room_status_online)
        }

        seatAdapter.submitList(seatItems(state))
        binding.textSeatsHint.text = when {
            !state.online -> getString(R.string.room_seats_hint_readonly)
            state.host -> getString(R.string.room_seats_hint_host)
            else -> getString(R.string.room_seats_hint_guest)
        }

        messageAdapter.submitList(state.messages)
        scrollToLatest(state)

        renderActions(state)
        settingsSheet?.render(state.snapshot, state.host, state.online)
    }

    private fun renderActions(state: RoomUiState) {
        val canAct = state.canAct

        binding.buttonReady.isVisible = !state.host
        binding.buttonStart.isVisible = state.host

        binding.buttonReady.isEnabled = canAct
        binding.buttonReady.text =
            getString(if (state.ready) R.string.room_cancel_ready else R.string.room_ready)

        // "还不能开"的原因由服务端给（startHint），这里只负责摆在按钮上，不自己判规则
        binding.buttonStart.text =
            state.startHint.ifEmpty { getString(R.string.room_start) }
        binding.buttonStart.isEnabled = canAct && state.startHint.isEmpty()

        binding.buttonSettings.isEnabled = canAct
    }

    private fun seatItems(state: RoomUiState): List<SeatInfo> = state.seats.map { seat ->
        SeatInfo(
            seat = seat.seat,
            name = seat.name ?: getString(R.string.room_seat_empty),
            tag = when (seat.tag) {
                SeatTag.HOST -> getString(R.string.room_seat_host)
                SeatTag.READY -> getString(R.string.room_seat_ready)
                SeatTag.WAITING -> getString(R.string.room_seat_waiting)
                SeatTag.NONE -> ""
            },
            occupied = seat.name != null,
            mine = seat.mine,
            avatar = seat.avatarId.takeIf { it.isNotEmpty() }?.let { avatarOf(it) },
        )
    }

    /**
     * 头像 id → 小图。没有（还没拉回来）就返回 `null`，座位卡先不画头像；
     * 拉回来之后 ViewModel 会再推一次状态，这里就能取到了。
     */
    private fun avatarOf(id: String): Bitmap? {
        avatarBitmaps[id]?.let { return it }
        val file = vm.blobFile(id) ?: return null
        val bitmap =
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        avatarBitmaps[id] = bitmap
        return bitmap
    }

    private fun scrollToLatest(state: RoomUiState) {
        val last = state.messages.lastOrNull()?.seq ?: return
        if (last == lastMessageSeq) return
        lastMessageSeq = last
        binding.recyclerMessages.scrollToPosition(state.messages.size - 1)
    }

    // ── 动作（都只是转给 ViewModel）────────────────────────────────────────

    private fun send() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        vm.say(text)
        binding.editMessage.setText("")
    }

    private fun openSettings() {
        val snapshot = uiState.snapshot ?: return
        settingsSheet?.show(snapshot, uiState.host, uiState.online)
    }

    private fun openSeatSheet(seat: Int) {
        val snapshot = uiState.snapshot ?: return
        if (!uiState.online) return
        showSeatSheet(
            activity = this,
            seat = seat,
            snapshot = snapshot,
            myId = uiState.myId,
            canArrange = uiState.host,
            onTakeSeat = { vm.takeSeat(it) },
            onSwap = { a, b -> vm.swapSeats(a, b) },
            onKick = { vm.kick(it) },
        )
    }
}
