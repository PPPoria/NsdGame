package com.ppporia.nsdgames.ui.rooms

import android.content.Intent
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.poria.base.ext.setOnClickListenerWithClickAnimation
import com.poria.base.ui.BaseActivity
import com.poria.core.game.GameCatalog
import com.poria.netnsd.FoundHost
import com.poria.netnsd.ServiceDiscovery
import com.ppporia.nsdgames.App
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityRoomsBinding
import com.ppporia.nsdgames.profile.ProfileStore
import com.ppporia.nsdgames.ui.Avatars
import com.ppporia.nsdgames.ui.room.RoomActivity
import com.ppporia.nsdgames.viewmodel.rooms.RoomsVM
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 首页：头像 + 昵称 + 创建房间 + 搜到的房间列表。
 *
 * 搜索只在页面可见时进行（NSD 没有"刷新"，只能 stop + start，`bindUi` 的 STARTED 生命周期正好对上）。
 *
 * **连接在这里建、也在这里收**（`App.session`）：
 * - [onStart] 一律 `session.leave()`，所以"首页可见 = 没有连接"，异常退出的残留也能兜住；
 * - 建房/加入交给 [RoomsVM]（它挂起建好连接后发 `openRoom`），页面只是拿事件去 startActivity；
 * - 房间页因此不需要 Intent 传参，也不需要知道自己是房主还是客人。
 *
 * 头像：只让用户"选图"，裁切/缩放交给 [Avatars]（居中裁正方形、压到 ≤512px）；
 * 选完立刻预览并存进 [ProfileStore]（DataStore），下次打开直接用，开房/加入时交给 `Session`。
 *
 * 昵称：输入时只更新"待写盘"的值，防抖后才落盘；离开页面时把没落盘的那次立刻写掉。
 */
class RoomsActivity : BaseActivity<ActivityRoomsBinding>() {

    private val vm by viewModels<RoomsVM>()

    private val adapter = RoomAdapter { join(it) }
    private var discovery: ServiceDiscovery? = null

    /** 系统相册选择器：Android 11+ 用系统 Photo Picker，不需要存储权限。 */
    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // 解码 + 裁正方形 + 压缩（都在 IO 线程），回来预览 + 落盘。
            lifecycleScope.launch {
                if (!vm.updateAvatar(this@RoomsActivity, uri)) {
                    Toast.makeText(
                        this@RoomsActivity,
                        R.string.rooms_avatar_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** 上次画过的头像：AvatarBytes 是按内容比较的，同一张图不会重复解码。 */
    private var shownAvatar: Avatars.AvatarBytes? = null

    override fun onCreateBinding(): ActivityRoomsBinding =
        ActivityRoomsBinding.inflate(layoutInflater)

    override fun initView() {
        binding.recyclerRooms.layoutManager = LinearLayoutManager(this)
        binding.recyclerRooms.adapter = adapter
        // 游戏列表来自 :core 的 GameCatalog，加游戏时这里不用改
        val games = GameCatalog.games.joinToString("、") { game ->
            if (game.available) game.title else getString(R.string.rooms_game_wip, game.title)
        }
        binding.textGames.text = getString(R.string.rooms_games, games)
    }

    override fun initListener() {
        // 1 秒防抖：连点两次不该开出两个房间
        binding.buttonCreate.setOnClickListenerWithClickAnimation(debounceMillis = 1_000) { createRoom() }
        binding.cardAvatar.setOnClickListenerWithClickAnimation(debounceMillis = 300) {
            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        // 昵称变更
        binding.editNickname.doAfterTextChanged(vm::onNicknameChanged)
    }

    override suspend fun bindUi() {
        startDiscovery()
        try {
            coroutineScope {
                launch { vm.openRoom.collect { openRoom() } }
                // 页面不可见时挂起在这里；被取消时走 finally 收掉扫描
                vm.state.collect { state ->
                    fillNickname(state.nickname)
                    showAvatar(state.avatar)
                    roomsRender(state.rooms.values.toList(), state.roomFoundNote, state.connecting)
                }
            }
        } finally {
            stopDiscovery()
        }
    }

    override fun onStart() {
        super.onStart()
        // 硬约束：**首页可见 = 没有连接**。进房间前是干净的，回到首页（不管是主动退出、
        // 被踢、还是房间解散）也一定是干净的——异常退出的残留同样会被这里收掉。
        lifecycleScope.launch { App.session.leave() }
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    private fun startDiscovery() {
        stopDiscovery()
        vm.roomsClear()
        val discovery = ServiceDiscovery(this, object : ServiceDiscovery.Listener {
            override fun onFound(host: FoundHost) {
                vm.roomsSet(host.serviceName, host)
            }

            override fun onLost(serviceName: String) {
                vm.roomsRemove(serviceName)
            }

            override fun onError(message: String) {
                vm.roomsFoundError(message)
            }
        })
        this.discovery = discovery
        discovery.start()
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    private fun roomsRender(list: List<FoundHost>, note: String?, connecting: Boolean) {
        adapter.submitList(list)
        binding.textEmpty.isVisible = list.isEmpty()
        binding.recyclerRooms.isVisible = list.isNotEmpty()
        binding.buttonCreate.isEnabled = !connecting
        binding.textStatus.text = when {
            connecting -> getString(R.string.rooms_connecting)
            else -> note ?: getString(R.string.rooms_status, list.size)
        }
    }

    private fun createRoom() {
        val name = nickname()
        vm.createRoom(
            roomName = getString(R.string.room_name_of, name),
            playerName = name,
            avatar = vm.state.value.avatar?.toByteArray(),
        )
    }

    private fun join(host: FoundHost) {
        vm.joinRoom(
            target = host,
            playerName = nickname(),
            avatar = vm.state.value.avatar?.toByteArray(),
        )
    }

    /** 连接建好了（房间页会自己从 `App.session` 拿）：这里只负责跳过去。 */
    private fun openRoom() {
        startActivity(Intent(this, RoomActivity::class.java))
    }

    private fun nickname(): String =
        binding.editNickname.text?.toString()?.trim().orEmpty()
            // 两台设备都留空会撞同一个默认昵称（服务端会拒绝重名），所以给个随机后缀
            .ifBlank { getString(R.string.default_nickname, Random.nextInt(10, 100)) }

    // ── 头像 ────────────────────────────────────────────────────────────────

    /** 头像只在真的换过时解码/重画（每次状态变化都解一张 512px 的图没必要）。 */
    private fun showAvatar(avatar: Avatars.AvatarBytes?) {
        if (avatar == shownAvatar) return
        shownAvatar = avatar
        val bitmap = avatar?.let { Avatars.decode(it) }
        binding.imageAvatar.setImageBitmap(bitmap)
        binding.textAvatarHint.isVisible = bitmap == null
    }

    // ── 昵称 ────────────────────────────────────────────────────────────────

    private fun fillNickname(nickname: String) {
        if (nickname.isNotEmpty() && binding.editNickname.text.isNullOrBlank()) {
            binding.editNickname.setText(nickname)
            binding.editNickname.setSelection(nickname.length)
        }
    }
}
