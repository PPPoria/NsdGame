package com.ppporia.nsdgames.profile

import com.poria.base.store.DSManager
import com.ppporia.nsdgames.ui.Avatars
import java.util.Base64
import kotlinx.coroutines.flow.map

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
    private const val KEY_SIGNATURE = "profile.signature"
    private const val KEY_AVATAR = "profile.avatar"

    val nicknameFlow = DSManager.getString(KEY_NICKNAME)
    val signatureFlow = DSManager.getString(KEY_SIGNATURE)
    val avatarFlow = DSManager.getString(KEY_AVATAR).map { encoded ->
        if (encoded.isEmpty()) null
        else runCatching {
            Base64.getDecoder().decode(encoded)
        }.getOrNull()?.let {
            Avatars.AvatarBytes(it)
        }
    }

    suspend fun saveNickname(name: String) {
        runCatching { DSManager.setString(KEY_NICKNAME, name.trim()) }
    }

    suspend fun saveSignature(signature: String) {
        runCatching { DSManager.setString(KEY_SIGNATURE, signature.trim()) }
    }

    suspend fun saveAvatar(avatar: Avatars.AvatarBytes?) {
        val encoded = avatar?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }.orEmpty()
        runCatching { DSManager.setString(KEY_AVATAR, encoded) }
    }
}
