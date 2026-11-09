package moe.lolosia.keel.plugins.basic.biz.db

/** 用户当前绑定的组（一条 `sys_user_roles` + `sys_role`）。 */
internal class RoleBinding(
    val bindingId: Long,
    val userId: String,
    val roleId: Long,
    val roleName: String?,
    val type: String?,
)
