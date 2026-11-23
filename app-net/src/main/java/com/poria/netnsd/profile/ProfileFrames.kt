package com.poria.netnsd.profile

import com.poria.netnsd.link.LinkCodec
import com.poria.netnsd.blob.BlobStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 一个人的**资料**（简历）：昵称 + 个性签名 + 头像 id。
 *
 * 它**不是权威数据**：座位、发言、房主权限全按 `playerId` 认，这份东西只影响显示。
 * 所以它可以晚到、可以随时改，也不会卡住建房 / 开局 / 换座。
 *
 * 头像只有 id，图片本体走 `blob` 频道（`Session.requestImage(avatarId, ownerId)`）。
 */
@Serializable
data class Profile(
    val name: String = "",
    /** 个性签名，可以空。 */
    val signature: String = "",
    /** 头像的内容 id（`sha1` 十六进制或 `builtin:` 素材）；空串 = 没设头像。 */
    val avatarId: String = "",
)

/**
 * `profile` 频道的负载。**推和拉是两件不同的事**，两条都要有：
 *
 * ```
 * 推（我愿意公开）      客户端 → 服务器  {"type":"put","profile":{…}}       ← 服务器盖 id 后广播
 *                      服务器 → 所有人  {"type":"info","id":"p2","profile":{…}}
 *
 * 拉（我想看看）        客户端 →（服务器中转）→ 本人  {"type":"want","playerId":"p2"}
 *                      本人 →（服务器中转）→ 问的人  {"type":"info","id":"p2","profile":{…}}
 * ```
 *
 * - **推**用在"我的资料变了"：改了头像/签名再 `put` 一次，所有人当场更新，不用等谁的快照；
 * - **拉**用在"我刚进来，名册上这些人我一个都不认识"：按 id 去问，**对方可以选择不答**
 *   （不想公开就是不回，问的人显示占位就行——这就是匿名模式，不需要额外的消息类型）；
 * - 两者的回答长得一样（`info`），所以接收侧只有一条路径。
 *
 * 谁发的由服务器盖章：`put` 和 `want` 里都没有"我是谁"，服务器用连接上认到的 id 覆盖落款，
 * 所以谁也改不了别人的资料。
 */
@Serializable
internal sealed interface ProfilePayload {

    /** 客户端 → 服务器：报上（或更新）我自己的资料，服务器广播出去。 */
    @Serializable
    @SerialName("put")
    data class Put(val profile: Profile) : ProfilePayload

    /** 客户端 → 本人（经服务器中转）：我想看看你的资料。**可以不回。** */
    @Serializable
    @SerialName("want")
    data class Want(val playerId: String) : ProfilePayload

    /** 服务器 → 所有人（广播）；或者 本人 → 问的人（回答）。 */
    @Serializable
    @SerialName("info")
    data class Info(val id: String, val profile: Profile) : ProfilePayload
}

/** `profile` 频道的编解码；和链路用同一套 JSON 配置。 */
internal object ProfileCodec {

    private val json = LinkCodec.json

    fun encode(payload: ProfilePayload): JsonElement = json.encodeToJsonElement(payload)

    fun decode(payload: JsonElement?): ProfilePayload? {
        if (payload == null) return null
        return runCatching { json.decodeFromJsonElement<ProfilePayload>(payload) }.getOrNull()
    }
}

/** 资料尺寸：昵称和签名都按"一行、一眼能看完"来限制。 */
internal object ProfileLimits {

    const val MAX_NAME = 12
    const val MAX_SIGNATURE = 40

    /** 头像内容 id 上限。 */
    const val MAX_AVATAR = 64

    /**
     * 把资料清洗成能收的样子（服务器侧和本人侧都用它，保证"我推的"和"我答的"一模一样）。
     *
     * 昵称/签名：去掉换行、截断（空就是空，UI 自己显示占位）；
     * 头像：只接受 `sha1` 十六进制或者 `builtin:` 素材，别的一律当没设。
     */
    fun sanitize(profile: Profile): Profile = Profile(
        name = clean(profile.name, MAX_NAME),
        signature = clean(profile.signature, MAX_SIGNATURE),
        avatarId = profile.avatarId.trim().take(MAX_AVATAR).lowercase().let { avatar ->
            when {
                avatar.isEmpty() -> ""
                BlobStore.isBuiltin(avatar) -> avatar
                BlobStore.isValidId(avatar) -> avatar
                else -> ""
            }
        },
    )

    private fun clean(value: String, max: Int): String =
        value.replace('\r', ' ').replace('\n', ' ').trim().take(max)
}
