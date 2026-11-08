package moe.lolosia.keel.plugins.rbac.api.dto

import kotlinx.serialization.Serializable

/**
 * 组的展示行，字段名与既有 `sys_role` 接口一致。
 *
 * 时间字段是展示字符串 `yyyy-MM-dd HH:mm:ss`（固定 +08:00），不是 `Instant`：
 * 本类型同时用于 HTTP 出参，序列化后前端直接渲染。
 *
 * @property menuId 已弃用的菜单字段，仅为前端兼容保留空数组
 * @property menuTitle 同上
 */
@Serializable
data class RoleRow(
    val id: Long,
    val roleName: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val deleted: Boolean = false,
    val menuId: List<Long> = emptyList(),
    val menuTitle: List<String> = emptyList(),
)
