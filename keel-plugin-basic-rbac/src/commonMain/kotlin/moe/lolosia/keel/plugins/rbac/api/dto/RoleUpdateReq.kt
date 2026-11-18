package moe.lolosia.keel.plugins.rbac.api.dto

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 修改组的入参：字段为 `null` 表示不修改。
 *
 * [id] 必填，用来说明改哪一行。
 */
@PublicApi
@Serializable
data class RoleUpdateReq(
    val id: Long,
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
)
