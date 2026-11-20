package com.ppporia.nsdgames.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.poria.base.ext.setOnClickListenerWithClickAnimation
import com.poria.base.ui.BaseActivity
import com.poria.core.game.GameCatalog
import com.poria.netnsd.FoundHost
import com.poria.netnsd.room.RoomSession
import com.poria.netnsd.room.RoomSnapshot
import com.poria.netnsd.room.RoomState
import com.poria.netnsd.room.SessionPhase
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityRoomBinding
import kotlin.random.Random

/**
 * 游戏准备房间页。房主和客人跑的是同一个 Activity：
 *
 * - 开房 / 加入的差别只在 Intent Extras，会话内部对 UI 不可见；
 * - **页面不存任何房间状态**：`state.collect { render }`，`render` 只读服务端快照；
 * - 所有按钮都只是"发一条命令"，界面要等服务端推回新快照才动。
 */
class RoomActivity : BaseActivity<ActivityRoomBinding>() {

    private var session: RoomSession? = null
    private var settingsSheet: RoomSettingsSheet? = null
    private val seatAdapter = SeatAdapter { seat -> openSeatSheet(seat) }
    private val messageAdapter = MessageAdapter()

    private var state = RoomState()
    private var lastMessageSeq = -1
    private var actionsBasePadding = 0

    /** 头像 id → 解好的小图。同一次会话里每个人最多解一次（512px WebP，解码就几毫秒）。 */
    private val avatarBitmaps = HashMap<String, Bitmap>()

    override fun onCreateBinding(): ActivityRoomBinding = ActivityRoomBinding.inflate(layoutInflater)

    override fun initView() {
        session = openSession()

        actionsBasePadding = binding.rowActions.paddingBottom

        binding.recyclerSeats.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerSeats.adapter = seatAdapter

        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = messageAdapter

        settingsSheet = RoomSettingsSheet(this).apply {
            listener = object : RoomSettingsSheet.Listener {
                override fun onSelectGame(gameId: String) {
                    session?.selectGame(gameId)
                }

                override fun onTableSize(size: Int) {
                    session?.setTableSize(size)
                }

                override fun onOption(optionId: String, on: Boolean) {
                    session?.setOption(optionId, on)
                }
            }
        }
    }

    override fun initListener() {
        binding.buttonLeave.setOnClickListenerWithClickAnimation { finish() }
        binding.buttonSend.setOnClickListenerWithClickAnimation(debounceMillis = 300) { send() }
        binding.buttonSettings.setOnClickListenerWithClickAnimation { openSettings() }
        binding.buttonReady.setOnClickListenerWithClickAnimation {
            // 只是把"我认为的当前状态取反"当作请求发出去，真正生效以服务端快照为准
            session?.setReady(!state.iAmReady)
        }
        binding.buttonStart.setOnClickListenerWithClickAnimation { session?.start() }
    }

    override suspend fun bindUi() {
        session?.state?.collect { render(it) }
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
        // close() 必须在主线程：它要调 NsdManager 注销广播。Activity 生命周期回调就是主线程。
        settingsSheet?.dismiss()
        settingsSheet = null
        session?.close()
        session = null
        super.onDestroy()
    }

    // ── 渲染（只读快照）────────────────────────────────────────────────────

    private fun render(next: RoomState) {
        state = next
        val snapshot = next.snapshot
        val game = snapshot?.let { GameCatalog.byId(it.gameId) }

        binding.textTitle.text = snapshot?.roomName?.takeIf { it.isNotBlank() } ?: getString(R.string.room_title)
        binding.textSubtitle.text = if (snapshot == null || game == null) {
            getString(R.string.room_connecting_short)
        } else {
            getString(R.string.room_header, game.title, snapshot.players.size, snapshot.tableSize)
        }
        binding.textStatus.text = when {
            next.note != null -> next.note
            next.phase == SessionPhase.CLOSED -> getString(R.string.room_status_closed)
            next.phase == SessionPhase.CONNECTING -> getString(R.string.room_status_connecting)
            snapshot != null && snapshot.hostId.isEmpty() -> getString(R.string.room_status_hostless)
            else -> getString(R.string.room_status_online)
        }

        // 谁有头像但本机还没有，就去要一张（session 自己会去重，重画多少次都无所谓）
        snapshot?.players?.forEach { player ->
            if (player.avatar.isNotEmpty()) session?.requestBlob(player.avatar)
        }

        seatAdapter.submitList(seatItems(snapshot, next))
        binding.textSeatsHint.text = when {
            !next.online -> getString(R.string.room_seats_hint_readonly)
            next.iAmHost -> getString(R.string.room_seats_hint_host)
            else -> getString(R.string.room_seats_hint_guest)
        }

        messageAdapter.submitList(messagesOf(snapshot, next.myId))
        scrollToLatest(snapshot)

        renderActions(next)
        settingsSheet?.render(snapshot, next.iAmHost, next.online)
    }

    private fun renderActions(state: RoomState) {
        val snapshot = state.snapshot
        val host = state.iAmHost
        val ready = state.online && snapshot != null

        binding.buttonReady.isVisible = !host
        binding.buttonStart.isVisible = host

        binding.buttonReady.isEnabled = ready
        binding.buttonReady.text =
            getString(if (state.iAmReady) R.string.room_cancel_ready else R.string.room_ready)

        // "还不能开"的原因由服务端给（startHint），这里只负责摆在按钮上，不自己判规则
        val hint = snapshot?.startHint.orEmpty()
        binding.buttonStart.text = if (hint.isEmpty()) getString(R.string.room_start) else hint
        binding.buttonStart.isEnabled = ready && hint.isEmpty()

        binding.buttonSettings.isEnabled = ready
    }

    /** 座位条：座位号来自快照的 tableSize，人来自快照的 players（客户端不自己攒）。 */
    private fun seatItems(snapshot: RoomSnapshot?, state: RoomState): List<SeatInfo> {
        if (snapshot == null) return emptyList()
        return (0 until snapshot.tableSize).map { seat ->
            val player = snapshot.playerAt(seat)
            SeatInfo(
                seat = seat,
                name = if (player == null) getString(R.string.room_seat_empty) else player.name,
                tag = when {
                    player == null -> ""
                    player.host -> getString(R.string.room_seat_host)
                    player.ready -> getString(R.string.room_seat_ready)
                    else -> getString(R.string.room_seat_waiting)
                },
                occupied = player != null,
                mine = player != null && player.id == state.myId,
                avatar = player?.avatar?.takeIf { it.isNotEmpty() }?.let { avatarOf(it) },
            )
        }
    }

    /**
     * 头像 id → 小图。没有（还没拉回来）就返回 null，座位卡先不画头像；
     * 拉回来之后快照/`availableBlobs` 会再推一次状态，这里再解码一次就有了。
     */
    private fun avatarOf(id: String): Bitmap? {
        avatarBitmaps[id]?.let { return it }
        val file = session?.blobFile(id) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        avatarBitmaps[id] = bitmap
        return bitmap
    }

    private fun scrollToLatest(snapshot: RoomSnapshot?) {
        val last = snapshot?.messages?.lastOrNull()?.seq ?: return
        if (last == lastMessageSeq) return
        lastMessageSeq = last
        val count = snapshot.messages.size
        if (count > 0) binding.recyclerMessages.scrollToPosition(count - 1)
    }

    // ── 动作（都只是发命令）────────────────────────────────────────────────

    private fun send() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        session?.say(text)
        binding.editMessage.setText("")
    }

    private fun openSettings() {
        val snapshot = state.snapshot ?: return
        settingsSheet?.show(snapshot, state.iAmHost, state.online)
    }

    private fun openSeatSheet(seat: Int) {
        val snapshot = state.snapshot ?: return
        if (!state.online) return
        showSeatSheet(
            activity = this,
            seat = seat,
            snapshot = snapshot,
            myId = state.myId,
            canArrange = state.iAmHost,
            onTakeSeat = { session?.takeSeat(it) },
            onSwap = { a, b -> session?.swapSeats(a, b) },
            onKick = { session?.kick(it) },
        )
    }

    private fun openSession(): RoomSession? {
        val player = intent.getStringExtra(EXTRA_PLAYER).orEmpty()
            .ifBlank { getString(R.string.default_nickname, Random.nextInt(10, 100)) }
        val avatar = intent.getByteArrayExtra(EXTRA_AVATAR)

        if (intent.getBooleanExtra(EXTRA_HOST, false)) {
            return RoomSession.host(this, intent.getStringExtra(EXTRA_ROOM).orEmpty(), player, avatar)
        }

        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (address == null || port <= 0) {
            finish()
            return null
        }
        return RoomSession.guest(
            this,
            FoundHost(
                serviceName = intent.getStringExtra(EXTRA_ROOM).orEmpty(),
                host = address,
                port = port,
            ),
            player,
            avatar,
        )
    }

    companion object {

        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_ROOM = "extra_room"
        private const val EXTRA_ADDRESS = "extra_address"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_PLAYER = "extra_player"
        private const val EXTRA_AVATAR = "extra_avatar"

        /** 开一间房：起服务端 + 广播端口 + 自己回环连上来。 */
        fun startHost(
            context: Context,
            roomName: String,
            playerName: String,
            avatar: ByteArray? = null,
        ) {
            context.startActivity(
                Intent(context, RoomActivity::class.java)
                    .putExtra(EXTRA_HOST, true)
                    .putExtra(EXTRA_ROOM, roomName)
                    .putExtra(EXTRA_PLAYER, playerName)
                    .putExtra(EXTRA_AVATAR, avatar)
            )
        }

        /** 加入搜到的房间。 */
        fun startGuest(
            context: Context,
            target: FoundHost,
            playerName: String,
            avatar: ByteArray? = null,
        ) {
            context.startActivity(
                Intent(context, RoomActivity::class.java)
                    .putExtra(EXTRA_HOST, false)
                    .putExtra(EXTRA_ROOM, target.serviceName)
                    .putExtra(EXTRA_ADDRESS, target.host)
                    .putExtra(EXTRA_PORT, target.port)
                    .putExtra(EXTRA_PLAYER, playerName)
                    .putExtra(EXTRA_AVATAR, avatar)
            )
        }
    }
}
