package com.ppporia.nsdgames.viewmodel.rooms

import android.content.Context
import android.net.Uri
import android.text.Editable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poria.netnsd.FoundHost
import com.ppporia.nsdgames.profile.ProfileStore
import com.ppporia.nsdgames.ui.Avatars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(FlowPreview::class)
class RoomsVM : ViewModel() {
    private val pendingNickname = MutableStateFlow<String?>(null)
    private val roomsFlow = MutableStateFlow(LinkedHashMap<String, FoundHost>())
    private var roomFoundNoteFlow = MutableStateFlow<String?>(null)
    val state = combine(
        ProfileStore.nicknameFlow,
        ProfileStore.avatarFlow,
        roomsFlow,
        roomFoundNoteFlow
    ) { nickname, avatar, rooms, note ->
        RoomsState(nickname, avatar, rooms, note)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RoomsState("", null, LinkedHashMap(), null)
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
        val roomFoundNote: String?
    )
}

