package moe.lolosia.keel.plugins.rbac.api.dto

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 新建组的入参。
 *
 * [roleName] 与 [type] 都会被校验为非空；[parentId] 不传即根基组。
 */
@PublicApi
@Serializable
data class RoleCreateReq(
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
)
