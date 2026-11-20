package com.ppporia.nsdgames.ui

import android.content.Context
import android.content.Intent
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.poria.base.ext.setOnClickListenerWithClickAnimation
import com.poria.base.ui.BaseActivity
import com.poria.netnsd.FoundHost
import com.poria.netnsd.chat.ChatPhase
import com.poria.netnsd.chat.ChatSession
import com.poria.netnsd.chat.ChatState
import com.ppporia.nsdgames.R
import com.ppporia.nsdgames.databinding.ActivityChatBinding
import kotlin.random.Random

/**
 * 聊天室页。**只管画和点**：所有状态来自 [ChatSession.state]，所有动作就是 [ChatSession.say]。
 *
 * 房主和客人跑的是同一个 Activity：区别只在 Extras（开房 vs 加入），会话内部的差异
 * 对 UI 不可见——这正是"房主自己也是一个客户端"想要的效果。
 */
class ChatActivity : BaseActivity<ActivityChatBinding>() {

    private var session: ChatSession? = null
    private val adapter = MessageAdapter()
    private var lastMessageCount = 0
    private var inputBasePadding = 0

    override fun onCreateBinding(): ActivityChatBinding = ActivityChatBinding.inflate(layoutInflater)

    override fun initView() {
        session = openSession()
        inputBasePadding = binding.rowInput.paddingBottom
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = adapter
    }

    override fun initListener() {
        binding.buttonLeave.setOnClickListenerWithClickAnimation { finish() }
        binding.buttonSend.setOnClickListenerWithClickAnimation(debounceMillis = 300) { send() }
    }

    override suspend fun bindUi() {
        session?.state?.collect { render(it) }
    }

    /**
     * 键盘弹起时把输入区顶上来的精确高度：系统栏内边距 BaseActivity 已经加过了，
     * 这里只补"IME 比系统栏多出来"的那部分，否则两者会叠加。
     */
    override fun onWindowInsetsChanged(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val extra = (ime.bottom - bars.bottom).coerceAtLeast(0)
        binding.rowInput.setPadding(
            binding.rowInput.paddingLeft,
            binding.rowInput.paddingTop,
            binding.rowInput.paddingRight,
            inputBasePadding + extra,
        )
    }

    override fun onDestroy() {
        // close() 必须在主线程：它要调 NsdManager 注销广播。Activity 生命周期回调就是主线程。
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun openSession(): ChatSession? {
        val player = intent.getStringExtra(EXTRA_PLAYER).orEmpty()
            .ifBlank { getString(R.string.default_nickname, Random.nextInt(10, 100)) }

        if (intent.getBooleanExtra(EXTRA_HOST, false)) {
            return ChatSession.host(this, intent.getStringExtra(EXTRA_ROOM).orEmpty(), player)
        }

        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (address == null || port <= 0) {
            finish()
            return null
        }
        return ChatSession.guest(
            FoundHost(
                serviceName = intent.getStringExtra(EXTRA_ROOM).orEmpty(),
                host = address,
                port = port,
            ),
            player,
        )
    }

    private fun render(state: ChatState) {
        binding.textTitle.text = state.roomName.ifBlank { getString(R.string.chat_title) }

        binding.textMembers.text = if (state.members.isEmpty()) {
            getString(R.string.chat_nobody)
        } else {
            getString(R.string.chat_members, state.members.size, state.members.joinToString("、") { it.name })
        }

        binding.textStatus.text = state.note ?: when (state.phase) {
            ChatPhase.ONLINE -> if (state.host) getString(R.string.chat_online_host) else getString(R.string.chat_online)
            ChatPhase.CLOSED -> getString(R.string.chat_closed)
            ChatPhase.CONNECTING -> getString(R.string.chat_connecting)
        }

        adapter.submitList(state.messages)
        if (state.messages.size != lastMessageCount) {
            lastMessageCount = state.messages.size
            if (lastMessageCount > 0) binding.recyclerMessages.scrollToPosition(lastMessageCount - 1)
        }
    }

    private fun send() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        session?.say(text)
        binding.editMessage.setText("")
    }

    companion object {

        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_ROOM = "extra_room"
        private const val EXTRA_ADDRESS = "extra_address"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_PLAYER = "extra_player"

        /** 开一间房：起服务端 + 广播端口 + 自己回环连上来。 */
        fun startHost(context: Context, roomName: String, playerName: String) {
            context.startActivity(
                Intent(context, ChatActivity::class.java)
                    .putExtra(EXTRA_HOST, true)
                    .putExtra(EXTRA_ROOM, roomName)
                    .putExtra(EXTRA_PLAYER, playerName)
            )
        }

        /** 加入搜到的房间。 */
        fun startGuest(context: Context, target: FoundHost, playerName: String) {
            context.startActivity(
                Intent(context, ChatActivity::class.java)
                    .putExtra(EXTRA_HOST, false)
                    .putExtra(EXTRA_ROOM, target.serviceName)
                    .putExtra(EXTRA_ADDRESS, target.host)
                    .putExtra(EXTRA_PORT, target.port)
                    .putExtra(EXTRA_PLAYER, playerName)
            )
        }
    }
}
