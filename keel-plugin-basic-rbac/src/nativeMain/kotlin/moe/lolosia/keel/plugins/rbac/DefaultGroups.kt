package moe.lolosia.keel.plugins.rbac

import moe.lolosia.keel.plugins.rbac.api.*

/**
 * 内置组的默认规则（对应 backend 的 `permissions.yaml`）。
 *
 * 语义：某个组在 `sys_role_permission` 里**一条规则都没有**时，判定回退用这里的默认值；
 * 库里一旦写了规则，一律以库为准，管理界面改过就不会被默认值覆盖。
 *
 * 规则串语法与判定口径见 `moe.lolosia.keel.api.web.Permissions`（负号优先、未命中拒绝）。
 * 这里的默认值刻意保持通用：owner / admin 拿全部节点，普通用户与访客不预置业务权限。
 */
internal object DefaultGroups {

    private val RULES: Map<String, List<String>> = mapOf(
        ROLE_TYPE_OWNER to listOf("+*"),
        ROLE_TYPE_ADMIN to listOf("+*"),
        ROLE_TYPE_USER to emptyList(),
        ROLE_TYPE_GUEST to emptyList(),
    )

    /** 组 type 的默认规则；未知组返回空。 */
    fun rulesOf(type: String?): List<String> =
        RULES[type?.trim()?.lowercase().orEmpty()].orEmpty()
}
