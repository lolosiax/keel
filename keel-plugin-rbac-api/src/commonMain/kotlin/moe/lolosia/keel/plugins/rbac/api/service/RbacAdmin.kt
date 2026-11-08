package moe.lolosia.keel.plugins.rbac.api.service

import moe.lolosia.keel.api.annotation.*
import moe.lolosia.keel.plugins.rbac.api.dto.*

/**
 * 管理侧的读写面：组、组规则、用户直授规则。
 *
 * 规则保存一律是**全量替换**（提交的列表即该主体最终的规则集合），非法规则串抛
 * `ApiException(400)` 且不落库；`operatorId` 写入审计列，传 `null` 表示没有操作者。
 *
 * 组的删除有两条业务约束：拥有者组不许删、仍被用户绑定的组不许删。
 */
@ModuleService("rbac.admin")
interface RbacAdmin {

    /** 分页查询组；[keyword] 同时模糊匹配展示名与代号。 */
    suspend fun pageRoles(pageNo: Int = 1, pageSize: Int = 10, keyword: String? = null): RolePage

    /** 新建组，返回含自增 id 的整行；缺少 `roleName` 或 `type` 抛 400。 */
    suspend fun createRole(req: RoleCreateReq, operatorId: String? = null): RoleRow

    /** 修改组：`null` 字段保持原值。 */
    suspend fun updateRole(req: RoleUpdateReq, operatorId: String? = null)

    /** 删除组（软删）：拥有者组或使用中的组抛错，不存在时静默返回。 */
    suspend fun deleteRole(id: Long, operatorId: String? = null)

    /** 全量替换某组的规则。 */
    suspend fun saveRoleRules(roleId: Long, rules: List<String>, operatorId: String? = null)

    /** 全量替换某用户的直授规则。 */
    suspend fun saveUserRules(userId: String, rules: List<String>, operatorId: String? = null)

    /** 给用户绑定一个组（已绑定同一组时按幂等处理）。 */
    suspend fun bindRole(userId: String, roleId: Long, operatorId: String? = null)

    /** 移除用户的组绑定。 */
    suspend fun unbindRole(userId: String, operatorId: String? = null)
}
