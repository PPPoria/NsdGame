package com.ppporia.nsdgames.utils

import android.app.Activity
import android.content.Intent
import com.poria.netnsd.FoundHost
import com.poria.netnsd.room.RoomSession
import com.ppporia.nsdgames.R
import kotlin.random.Random

private const val EXTRA_HOST = "extra_host"
private const val EXTRA_ROOM = "extra_room"
private const val EXTRA_ADDRESS = "extra_address"
private const val EXTRA_PORT = "extra_port"
private const val EXTRA_PLAYER = "extra_player"
private const val EXTRA_AVATAR = "extra_avatar"

fun Activity.openRoomSession(): RoomSession? {
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

fun Intent.putExtraForRoomSessionHost(
    roomName: String,
    playerName: String,
    avatar: ByteArray? = null,
): Intent = this
    .putExtra(EXTRA_HOST, true)
    .putExtra(EXTRA_ROOM, roomName)
    .putExtra(EXTRA_PLAYER, playerName)
    .putExtra(EXTRA_AVATAR, avatar)

fun Intent.putExtraForRoomSessionGuest(
    target: FoundHost,
    playerName: String,
    avatar: ByteArray? = null,
): Intent = this
    .putExtra(EXTRA_HOST, false)
    .putExtra(EXTRA_ROOM, target.serviceName)
    .putExtra(EXTRA_ADDRESS, target.host)
    .putExtra(EXTRA_PORT, target.port)
    .putExtra(EXTRA_PLAYER, playerName)
    .putExtra(EXTRA_AVATAR, avatar)