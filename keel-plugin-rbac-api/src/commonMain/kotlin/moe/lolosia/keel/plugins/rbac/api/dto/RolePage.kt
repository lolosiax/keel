package moe.lolosia.keel.plugins.rbac.api.dto

import kotlinx.serialization.Serializable

/**
 * 组的分页结果。
 *
 * 键名是 `rows` / `total`（前端组列表按这两个键读取），与用户分页的 `data` / `total` 不同名。
 */
@Serializable
data class RolePage(
    val rows: List<RoleRow> = emptyList(),
    val total: Long = 0L,
)
