package com.ppporia.nsdgames.profile

import com.poria.base.store.DSManager
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 本机的"我是谁"：昵称 + 头像。存在 base 的 [DSManager]（DataStore Preferences）里，key 前缀 `profile.`。
 *
 * - **昵称**是纯文本，输入框里防抖后写一次（见 `RoomsActivity.requestNicknameSave`）；
 * - **头像是编码好的 WebP（≤512px）转 base64 的字符串**——DataStore Preferences 没有 byte[]
 *   类型，所以走文本；一张头像一般 20–60KB，base64 后 30–80KB，只在换头像时写一次。
 *
 * 读失败（文件损坏之类）一律回默认值，绝不让首页因为存储坏了打不开。
 */
object ProfileStore {

    private const val KEY_NICKNAME = "profile.nickname"
    private const val KEY_AVATAR = "profile.avatar"

    /**
     * 进程级 scope：页面马上要停（`onStop` 的收尾写）时用，别跟着 Activity 一起被取消。
     * 只做一次性的小写入，写失败也只是丢一次昵称，下次输入会再写。
     */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun nickname(): String =
        runCatching { DSManager.getString(KEY_NICKNAME).first() }.getOrDefault("").trim()

    suspend fun saveNickname(name: String) {
        runCatching { DSManager.setString(KEY_NICKNAME, name.trim()) }
    }

    /** 页面即将结束时用：不阻塞、也不会因为 `lifecycleScope` 被取消而丢掉这次写入。 */
    fun saveNicknameAsync(name: String) {
        io.launch { saveNickname(name) }
    }

    suspend fun avatar(): ByteArray? {
        val encoded = runCatching { DSManager.getString(KEY_AVATAR).first() }.getOrDefault("")
        if (encoded.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    suspend fun saveAvatar(bytes: ByteArray?) {
        val encoded = bytes?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()
        runCatching { DSManager.setString(KEY_AVATAR, encoded) }
    }
}
