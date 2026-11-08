package moe.lolosia.keel.plugins.rbac.api.dto

import kotlinx.serialization.Serializable

/**
 * 新建组的入参。
 *
 * [roleName] 与 [type] 都会被校验为非空；[parentId] 不传即根基组。
 */
@Serializable
data class RoleCreateReq(
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
)
