package com.ppporia.nsdgames.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.poria.base.ext.setOnClickListenerWithClickAnimation
import com.poria.base.ui.BaseActivity
import com.poria.core.game.GameCatalog
import com.poria.netnsd.FoundHost
import com.poria.netnsd.ServiceDiscovery
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityRoomsBinding
import com.ppporia.nsdgames.profile.ProfileStore
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 昵称防抖：停下 500ms 才写一次盘；连续输入最多合并成一次写入。 */
private const val NICKNAME_SAVE_DEBOUNCE_MS = 500L

/**
 * 首页：头像 + 昵称 + 创建房间 + 搜到的房间列表。
 *
 * 搜索只在页面可见时进行（NSD 没有"刷新"，只能 stop + start，`bindUi` 的 STARTED 生命周期正好对上）。
 * 这里不碰 socket 也不碰协议：点房间就是把 `FoundHost` 交给 [RoomActivity]。
 *
 * 头像：只让用户"选图"，裁切/缩放交给 [Avatars]（居中裁正方形、压到 ≤512px）；
 * 选完立刻预览并存进 [ProfileStore]（DataStore），下次打开直接用，开房/加入时随 Intent 带进房间。
 *
 * 昵称：输入时只更新"待写盘"的值，防抖后才落盘；离开页面时把没落盘的那次立刻写掉。
 */
class RoomsActivity : BaseActivity<ActivityRoomsBinding>() {

    private val rooms = LinkedHashMap<String, FoundHost>()
    private val adapter = RoomAdapter { join(it) }
    private var discovery: ServiceDiscovery? = null

    /** 用户选的头像（已编码好的 ≤512px WebP）；null = 没选。 */
    private var avatarBytes: ByteArray? = null

    /** 还没写盘的昵称；和 [saveJob] 一起做防抖（连续输入只写最后一次）。 */
    private var pendingNickname: String? = null
    private var saveJob: Job? = null

    /** 上一次真正写进 DataStore 的昵称：用来跳过"填回输入框"触发的无意义写入。 */
    private var lastSavedNickname: String? = null

    /** 只在第一次进入页面时把本地资料填回输入框，避免从房间回来时覆盖用户正在敲的字。 */
    private var profileLoaded = false

    /** 系统相册选择器：Android 11+ 用系统 Photo Picker，不需要存储权限。 */
    private val pickAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadAvatar(uri)
    }

    override fun onCreateBinding(): ActivityRoomsBinding = ActivityRoomsBinding.inflate(layoutInflater)

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
        // 昵称变更：先记下来，500ms 没再敲才写盘
        binding.editNickname.doAfterTextChanged { editable ->
            requestNicknameSave(editable?.toString().orEmpty())
        }
    }

    /** 每次回到前台都会跑一次，但资料只填一次（见 [profileLoaded]）。 */
    override suspend fun loadData() {
        if (profileLoaded) return
        profileLoaded = true

        val savedName = ProfileStore.nickname()
        if (savedName.isNotEmpty() && binding.editNickname.text.isNullOrBlank()) {
            // 先记下来，免得 setText 触发 TextWatcher 再写一遍同样的值
            lastSavedNickname = savedName
            binding.editNickname.setText(savedName)
            binding.editNickname.setSelection(savedName.length)
        } else {
            lastSavedNickname = binding.editNickname.text?.toString()
        }

        ProfileStore.avatar()?.let { bytes ->
            avatarBytes = bytes
            showAvatar(bytes)
        }
    }

    override suspend fun bindUi() {
        startDiscovery()
        try {
            // 页面不可见时挂起在这里；被取消时走 finally 收掉扫描
            awaitCancellation()
        } finally {
            stopDiscovery()
        }
    }

    override fun onStop() {
        // 切后台 / 进房间：把还没落盘的昵称立刻写掉
        flushNickname()
        super.onStop()
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    private fun startDiscovery() {
        stopDiscovery()
        rooms.clear()
        val discovery = ServiceDiscovery(this, object : ServiceDiscovery.Listener {
            override fun onFound(host: FoundHost) {
                rooms[host.serviceName] = host
                render(note = null)
            }

            override fun onLost(serviceName: String) {
                rooms.remove(serviceName)
                render(note = null)
            }

            override fun onError(message: String) {
                render(note = message)
            }
        })
        this.discovery = discovery
        discovery.start()
        render(note = null)
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    private fun render(note: String?) {
        val list = rooms.values.toList()
        adapter.submitList(list)
        binding.textEmpty.isVisible = list.isEmpty()
        binding.recyclerRooms.isVisible = list.isNotEmpty()
        binding.textStatus.text = note ?: getString(R.string.rooms_status, list.size)
    }

    private fun createRoom() {
        val name = nickname()
        RoomActivity.startHost(
            this,
            roomName = getString(R.string.room_name_of, name),
            playerName = name,
            avatar = avatarBytes,
        )
    }

    private fun join(host: FoundHost) {
        RoomActivity.startGuest(this, target = host, playerName = nickname(), avatar = avatarBytes)
    }

    private fun nickname(): String =
        binding.editNickname.text?.toString()?.trim().orEmpty()
            // 两台设备都留空会撞同一个默认昵称（服务端会拒绝重名），所以给个随机后缀
            .ifBlank { getString(R.string.default_nickname, Random.nextInt(10, 100)) }

    // ── 头像 ────────────────────────────────────────────────────────────────

    /** 解码 + 裁正方形 + 压缩（都在 IO 线程），回来预览 + 落盘。 */
    private fun loadAvatar(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { Avatars.encode(this@RoomsActivity, uri) }
            if (bytes == null) {
                Toast.makeText(this@RoomsActivity, R.string.rooms_avatar_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            avatarBytes = bytes
            showAvatar(bytes)
            ProfileStore.saveAvatar(bytes) // 选中就存，下次打开直接用
        }
    }

    private fun showAvatar(bytes: ByteArray) {
        val bitmap = Avatars.decode(bytes)
        binding.imageAvatar.setImageBitmap(bitmap)
        binding.textAvatarHint.isVisible = bitmap == null
    }

    // ── 昵称：防抖写 DataStore ──────────────────────────────────────────────

    /**
     * 把最新值挂在 [pendingNickname] 上，由一个协程在 500ms 空闲后落盘：
     *
     * - 连续输入只会写一次；写的过程中又敲字，循环会把最新值接着写掉（不丢最后一次）；
     * - 不取消/重建协程，所以没有"每次按键起一个 Job"的抖动。
     */
    private fun requestNicknameSave(text: String) {
        if (text == lastSavedNickname) return
        pendingNickname = text
        if (saveJob?.isActive == true) return
        saveJob = lifecycleScope.launch {
            while (true) {
                delay(NICKNAME_SAVE_DEBOUNCE_MS)
                val value = pendingNickname ?: break
                pendingNickname = null
                ProfileStore.saveNickname(value)
                lastSavedNickname = value
            }
        }
    }

    /** 立刻写掉待写的昵称（离开页面时用）。重复调用安全。 */
    private fun flushNickname() {
        saveJob?.cancel()
        saveJob = null
        val value = pendingNickname ?: return
        pendingNickname = null
        lastSavedNickname = value
        // 用进程级 scope 写：页面马上要停，lifecycleScope 里的协程会被取消，
        // 但这次写入应该落盘（DataStore 的 edit 本身是原子的，不会留半份数据）。
        ProfileStore.saveNicknameAsync(value)
    }
}
