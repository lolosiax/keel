package moe.lolosia.keel.plugins.rbac.api.dto

import kotlinx.serialization.Serializable

/**
 * 组的轻量引用，只带判定与展示需要的字段。
 *
 * 审计列（`createdAt` / `createdBy` 等）不进这里，管理界面需要时用 [RoleRow]。
 *
 * @property id 组 id（`sys_role.id`），与 `ROLE_ID_*` 常量同一取值域
 * @property name 展示名（`sys_role.role_name`），可改
 * @property type 稳定代号（`owner` / `admin` / `user` / `guest`），代码按它判定
 * @property parentId 父组 id（组继承），`null` 为根基组
 */
@Serializable
data class RoleRef(
    val id: Long,
    val name: String? = null,
    val type: String? = null,
    val parentId: Long? = null,
)
