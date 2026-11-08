package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable

/**
 * 修改用户的入参：字段为 `null` 表示不修改。
 *
 * [password] 为空串或 `null` 时不改密码；[roleId] 给了就顺带改组绑定（没有绑定则补一条）。
 */
@Serializable
data class UserUpdateReq(
    val id: String,
    val userName: String? = null,
    val password: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val isUse: Int? = null,
    val roleId: Long? = null,
)
