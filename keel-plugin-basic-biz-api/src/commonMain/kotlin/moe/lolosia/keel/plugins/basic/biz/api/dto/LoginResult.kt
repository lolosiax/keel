package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 登录成功的出参：用户信息 + 会话 id。
 *
 * [authorization] 序列化后的键是 `Authorization`，前端只从它取 token，之后按
 * `Authorization: Bearer <token>` 带回。它是**会话 id**，不是 JWT。
 *
 * 本类型只用于 HTTP 面；别的插件不该替用户登录，所以没有任何 Service 接口返回它。
 *
 * @property firstLogin 库里没密码且本次也没传密码（首次登录）
 */
@Serializable
data class LoginResult(
    val id: String,
    val userName: String,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    @SerialName("Authorization") val authorization: String,
    val firstLogin: Boolean = false,
)
