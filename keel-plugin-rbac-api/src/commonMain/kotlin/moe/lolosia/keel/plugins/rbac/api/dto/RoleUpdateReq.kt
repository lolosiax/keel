package moe.lolosia.keel.plugins.rbac.api.dto

import kotlinx.serialization.Serializable

/**
 * 修改组的入参：字段为 `null` 表示不修改。
 *
 * [id] 必填，用来说明改哪一行。
 */
@Serializable
data class RoleUpdateReq(
    val id: Long,
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
)
