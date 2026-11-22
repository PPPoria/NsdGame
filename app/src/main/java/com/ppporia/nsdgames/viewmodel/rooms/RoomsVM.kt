package com.ppporia.nsdgames.viewmodel.rooms

import android.content.Context
import android.net.Uri
import android.text.Editable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poria.netnsd.FoundHost
import com.ppporia.nsdgames.App
import com.ppporia.nsdgames.profile.ProfileStore
import com.ppporia.nsdgames.ui.Avatars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 昵称防抖：停下 500ms 才写一次盘；连续输入最多合并成一次写入。 */
private const val NICKNAME_SAVE_DEBOUNCE_MS = 500L

/**
 * 首页的 ViewModel：昵称/头像 + 搜房间 + **建连接**。
 *
 * 连接由这里发起（`session.host` / `session.join`），房间页只管订阅——因为首页是整个流程的入口，
 * 而且首页有一条硬约束：**可见 = 没有连接**（`RoomsActivity.onStart` 会 `session.leave()`）。
 * 于是"连不上"的失败信息留在首页显示（[RoomsState.roomFoundNote]），不用带进下一个页面。
 */
@OptIn(FlowPreview::class)
class RoomsVM : ViewModel() {

    /** 进程里唯一的会话（`App` 的伴生对象）；这里只是引用，不负责它的生死。 */
    private val session = App.session
    private val pendingNickname = MutableStateFlow<String?>(null)
    private val roomsFlow = MutableStateFlow(LinkedHashMap<String, FoundHost>())
    private val roomFoundNoteFlow = MutableStateFlow<String?>(null)

    /** 连接中：按钮置灰，防连点开出两个房间。 */
    private val connectingFlow = MutableStateFlow(false)

    /** 连好了：Activity 收到就去开房间页（一次性事件，不重放）。 */
    private val _openRoom = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openRoom: SharedFlow<Unit> = _openRoom

    val state = combine(
        ProfileStore.nicknameFlow,
        ProfileStore.avatarFlow,
        roomsFlow,
        roomFoundNoteFlow,
        connectingFlow,
    ) { nickname, avatar, rooms, note, connecting ->
        RoomsState(nickname, avatar, rooms, note, connecting)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RoomsState("", null, LinkedHashMap(), null, false)
    )

    init {
        viewModelScope.launch {
            pendingNickname
                .filterNotNull()
                .debounce(NICKNAME_SAVE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { nickname ->
                    try {
                        ProfileStore.saveNickname(nickname)
                        pendingNickname.compareAndSet(nickname, null)
                    } catch (e: Exception) {
                        // 写入失败：pending 保留，可重试或提示；
                        // 不要在这里把 pending 置 null，否则这次输入就丢了
                    }
                }
        }
    }

    fun onNicknameChanged(editable: Editable?) {
        pendingNickname.value = editable?.toString().orEmpty()
    }

    suspend fun updateAvatar(context: Context, uri: Uri): Boolean {
        val avatarBytes = withContext(Dispatchers.IO) { Avatars.encode(context, uri) }
        if (avatarBytes == null) return false
        ProfileStore.saveAvatar(avatarBytes) // 选中就存，下次打开直接用
        return true
    }

    // ── 建房 / 加入（连接在这里建，房间页只订阅）──────────────────────────────

    fun createRoom(roomName: String, playerName: String, avatar: ByteArray?) {
        connect { session.host(roomName, playerName, avatar) }
    }

    fun joinRoom(target: FoundHost, playerName: String, avatar: ByteArray?) {
        connect { session.join(target, playerName, avatar) }
    }

    /**
     * 建连接：成功就发 [openRoom]（让 Activity 开房间页），失败把原因写到状态栏。
     *
     * @param block 返回 `null` 表示成功，否则是给人看的失败原因
     */
    private fun connect(block: suspend () -> String?) {
        if (connectingFlow.value) return
        viewModelScope.launch {
            connectingFlow.value = true
            val error = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "连接失败：${e.message}"
            } finally {
                connectingFlow.value = false
            }
            if (error == null) {
                roomFoundNoteFlow.value = null
                _openRoom.tryEmit(Unit)
            } else {
                roomFoundNoteFlow.value = error
            }
        }
    }

    // ── 搜到的房间 ──────────────────────────────────────────────────────────

    fun roomsClear() {
        roomsFlow.update { old ->
            LinkedHashMap(old).apply { clear() }
        }
    }

    fun roomsSet(key: String, value: FoundHost) {
        roomsFlow.update { old ->
            LinkedHashMap(old).apply { set(key, value) }
        }
    }

    fun roomsRemove(key: String) {
        roomsFlow.update { old ->
            LinkedHashMap(old).apply { remove(key) }
        }
    }

    fun roomsFoundError(message: String) {
        roomFoundNoteFlow.value = message
    }

    data class RoomsState(
        val nickname: String,
        val avatar: Avatars.AvatarBytes?,
        val rooms: LinkedHashMap<String, FoundHost>,
        val roomFoundNote: String?,
        /** 正在建房/进房：按钮置灰。 */
        val connecting: Boolean = false,
    )
}
