package com.poria.netnsd.profile

import com.poria.netnsd.link.TcpLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `profile` 频道的测试：真开 socket（`TcpLink`）+ 纯逻辑各一半。
 *
 * 覆盖推（`put` → 所有人）和拉（`want` → 本人回答，或者干脆不回）两条路，
 * 以及"服务器盖章"这条防伪线。
 */
class ProfileChannelTest {

    private class Host(val link: TcpLink, val channel: ProfileChannel, val port: Int)

    private fun startHost(): Host {
        val channel = ProfileChannel()
        val link = TcpLink().apply { register(channel) }
        val port = link.host(0)
        return Host(link, channel, port)
    }

    private fun joinGuest(port: Int): Pair<TcpLink, ProfileChannel> {
        val channel = ProfileChannel()
        val link = TcpLink().apply { register(channel) }
        link.join("127.0.0.1", port)
        return link to channel
    }

    private fun await(timeoutMs: Long = 10_000, message: String = "等超时了", block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    // ── 纯逻辑 ──────────────────────────────────────────────────────────────

    @Test
    fun `清洗：昵称去换行截断，头像只认 sha1 和 builtin`() {
        val cleaned = ProfileLimits.sanitize(
            Profile(name = "  小明\n第二行  ", signature = "签名".repeat(50), avatarId = "../../etc/passwd"),
        )
        assertEquals("小明 第二行", cleaned.name)
        assertEquals(ProfileLimits.MAX_SIGNATURE, cleaned.signature.length)
        assertEquals("非法 id 当没设", "", cleaned.avatarId)

        assertEquals("builtin:azul.grey", ProfileLimits.sanitize(Profile(avatarId = "builtin:azul.grey")).avatarId)

        val sha = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(sha, ProfileLimits.sanitize(Profile(avatarId = sha.uppercase())).avatarId)
        assertEquals("名".repeat(ProfileLimits.MAX_NAME), ProfileLimits.sanitize(Profile(name = "名".repeat(50))).name)
    }

    @Test
    fun `负载往返，坏包返回 null`() {
        val payload = ProfileCodec.encode(ProfilePayload.Put(Profile("小明", "签名", "")))
        assertEquals(ProfilePayload.Put(Profile("小明", "签名", "")), ProfileCodec.decode(payload))
        assertNull(ProfileCodec.decode(null))
        assertNull(ProfileCodec.decode(com.poria.netnsd.link.LinkCodec.json.parseToJsonElement("{}")))
    }

    // ── 推：我报资料，所有人看得到 ──────────────────────────────────────────

    @Test(timeout = 40_000)
    fun `推：报上资料后所有人看得到，包括自己那条回环客户端`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            val profile = Profile(name = "客人", signature = "你好")
            guestChannel.publish(profile)

            await(message = "服务器那边没收到") { host.channel.profile(guest.playerId)?.name == "客人" }
            await(message = "客人自己没收到广播") { guestChannel.profile(guest.playerId)?.signature == "你好" }
            assertEquals(profile, host.channel.profile(guest.playerId))
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `推：改了资料再发一次，所有人当场更新`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            guestChannel.publish(Profile(name = "客人", avatarId = ""))
            await(message = "第一次没到") { host.channel.profile(guest.playerId)?.name == "客人" }

            val sha = "0123456789abcdef0123456789abcdef01234567"
            guestChannel.publish(Profile(name = "客人改名了", avatarId = sha))
            await(message = "更新没到") { host.channel.profile(guest.playerId)?.avatarId == sha }
            assertEquals("客人改名了", host.channel.profile(guest.playerId)?.name)
        } finally {
            guest.close()
            host.link.close()
        }
    }

    @Test(timeout = 40_000)
    fun `服务器盖章：谁也改不了别人的资料`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)
        val (second, secondChannel) = joinGuest(host.port)

        try {
            firstChannel.publish(Profile(name = "第一个"))
            secondChannel.publish(Profile(name = "第二个"))

            await(message = "两个人的资料都该到服务器") {
                host.channel.profile(first.playerId)?.name == "第一个" &&
                    host.channel.profile(second.playerId)?.name == "第二个"
            }
            await(message = "彼此也看得到对方") {
                secondChannel.profile(first.playerId)?.name == "第一个" &&
                    firstChannel.profile(second.playerId)?.name == "第二个"
            }
        } finally {
            second.close()
            first.close()
            host.link.close()
        }
    }

    // ── 拉：我向你要，你可以不给 ────────────────────────────────────────────

    @Test(timeout = 40_000)
    fun `拉：新来的人按 id 把老玩家的资料要一遍`() {
        val host = startHost()
        val (first, firstChannel) = joinGuest(host.port)

        try {
            // 先来的那位报过资料（推过一次），但新来的人当时还没连上，收不到
            firstChannel.publish(Profile(name = "先来的", signature = "早"))
            await(message = "服务器没收到") { host.channel.profile(first.playerId) != null }

            val (second, secondChannel) = joinGuest(host.port)
            try {
                assertNull("新人一开始不知道他", secondChannel.profile(first.playerId))

                // 相当于 VM 拿到名册之后做的事
                secondChannel.sync(listOf(first.playerId))

                await(message = "问了一次没拿到资料") {
                    secondChannel.profile(first.playerId)?.name == "先来的"
                }
                assertEquals("问过之后就清了 pending", emptySet<String>(), secondChannel.pending.value)
            } finally {
                second.close()
            }
        } finally {
            first.close()
            host.link.close()
        }
    }

    @Test(timeout = 20_000)
    fun `拉：对方不公开就不回，问的人也不会反复问`() {
        val host = startHost()
        val (shy, shyChannel) = joinGuest(host.port)

        try {
            // shy 从不 publish：匿名模式
            val (asker, askerChannel) = joinGuest(host.port)
            try {
                assertTrue("第一次问会发出去", askerChannel.want(shy.playerId))
                assertFalse("同一个 id 不会问第二遍", askerChannel.want(shy.playerId))
                assertEquals("已经记在 pending 里", setOf(shy.playerId), askerChannel.pending.value)

                askerChannel.sync(listOf(shy.playerId)) // 名册又变了一次
                assertEquals("sync 也不会重复问", setOf(shy.playerId), askerChannel.pending.value)

                Thread.sleep(300)
                assertNull("对方不公开，问的人一直拿不到", askerChannel.profile(shy.playerId))
                assertEquals("但会记住问过了", setOf(shy.playerId), askerChannel.pending.value)
                assertNull("对方自己那份本来就不存在", shyChannel.profile(shy.playerId))
            } finally {
                asker.close()
            }
        } finally {
            shy.close()
            host.link.close()
        }
    }

    // ── 防伪 ────────────────────────────────────────────────────────────────

    @Test(timeout = 20_000)
    fun `只认本人回答和服务器广播，落款对不上的资料被丢掉`() {
        val host = startHost()
        val (guest, guestChannel) = joinGuest(host.port)

        try {
            // 模拟"某某帮你带话"：落款不是资料的主人
            guestChannel.onEvent(
                ProfileCodec.encode(ProfilePayload.Info(host.link.playerId, Profile(name = "我是房主"))),
                from = guest.playerId,
            )
            assertNull("落款和 id 对不上，丢掉", guestChannel.profile(host.link.playerId))
        } finally {
            guest.close()
            host.link.close()
        }
    }
}
