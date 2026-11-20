package com.ppporia.nsdgames.ui

import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.poria.base.ext.setOnClickListenerWithClickAnimation
import com.poria.base.ui.BaseActivity
import com.poria.core.game.GameCatalog
import com.poria.netnsd.FoundHost
import com.poria.netnsd.ServiceDiscovery
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityRoomsBinding
import kotlin.random.Random
import kotlinx.coroutines.awaitCancellation

/**
 * 首页：搜房间 + 开房间。
 *
 * 搜索只在页面可见时进行（NSD 没有"刷新"，只能 stop + start，`bindUi` 的 STARTED 生命周期正好对上）。
 * 这里不碰 socket 也不碰协议：点房间就是把 `FoundHost` 交给 [RoomActivity]。
 */
class RoomsActivity : BaseActivity<ActivityRoomsBinding>() {

    private val rooms = LinkedHashMap<String, FoundHost>()
    private val adapter = RoomAdapter { join(it) }
    private var discovery: ServiceDiscovery? = null

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
        RoomActivity.startHost(this, roomName = getString(R.string.room_name_of, name), playerName = name)
    }

    private fun join(host: FoundHost) {
        RoomActivity.startGuest(this, target = host, playerName = nickname())
    }

    private fun nickname(): String =
        binding.editNickname.text?.toString()?.trim().orEmpty()
            // 两台设备都留空会撞同一个默认昵称（服务端会拒绝重名），所以给个随机后缀
            .ifBlank { getString(R.string.default_nickname, Random.nextInt(10, 100)) }
}
