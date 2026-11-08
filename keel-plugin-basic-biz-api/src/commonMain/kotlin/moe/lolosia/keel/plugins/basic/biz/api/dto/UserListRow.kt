package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable

/**
 * 用户列表行：用户字段 + 其组信息（`view_user_role` 的一行）。
 *
 * 账户已不存在时保留视图里的组信息，[realName] 给占位文案。
 *
 * @property id 用户 id（视图里的 `id` 是绑定 id，合并时被用户覆盖）
 * @property type 组代号
 * @property roleType 与 [type] 同值，旧版两个键都给前端，故都保留
 */
@Serializable
data class UserListRow(
    val id: String,
    val userId: String,
    val userName: String? = null,
    val realName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val isUse: Int? = null,
    val roleId: Long? = null,
    val roleName: String? = null,
    val type: String? = null,
    val roleType: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
