package moe.lolosia.keel.plugins.rbac

import moe.lolosia.keel.api.annotation.Singleton
import moe.lolosia.keel.plugins.rbac.api.dto.*
import moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin
import moe.lolosia.keel.plugins.rbac.api.service.RbacReader
import moe.lolosia.keel.plugins.rbac.db.RbacRepository

/**
 * rbac 的默认实现：同时提供判定侧 [RbacReader] 与管理侧 [RbacAdmin]。
 *
 * 两个契约共用一个实例，KSP 会按两个 typeId 各登记一次单例。替换实现只要实现同一组
 * 接口（同一个 typeId）就能原位顶替，调用方不需要改。
 */
@Singleton
class RbacService : RbacReader, RbacAdmin {

    override suspend fun roleOf(userId: String): RoleRef? = RbacRepository.roleOf(userId)

    override suspend fun rolesOf(userId: String): List<RoleRef> = RbacRepository.rolesOf(userId)

    override suspend fun roleByType(type: String): RoleRef? = RbacRepository.roleByType(type)

    override suspend fun effectiveRules(userId: String): List<String> =
        RbacRepository.effectiveRules(userId)

    override suspend fun rulesOfRole(roleId: Long): List<String> = RbacRepository.rulesOfRole(roleId)

    override suspend fun rulesOfUser(userId: String): List<String> = RbacRepository.rulesOfUser(userId)

    override suspend fun listRoles(): List<RoleRef> = RbacRepository.listRoles()

    override suspend fun isOwner(userId: String): Boolean = RbacRepository.isOwner(userId)

    override suspend fun pageRoles(pageNo: Int, pageSize: Int, keyword: String?): RolePage =
        RbacRepository.pageRoles(pageNo, pageSize, keyword)

    override suspend fun createRole(req: RoleCreateReq, operatorId: String?): RoleRow =
        RbacRepository.createRole(req, operatorId)

    override suspend fun updateRole(req: RoleUpdateReq, operatorId: String?) =
        RbacRepository.updateRole(req, operatorId)

    override suspend fun deleteRole(id: Long, operatorId: String?) =
        RbacRepository.deleteRole(id, operatorId)

    override suspend fun saveRoleRules(roleId: Long, rules: List<String>, operatorId: String?) =
        RbacRepository.saveRoleRules(roleId, rules, operatorId)

    override suspend fun saveUserRules(userId: String, rules: List<String>, operatorId: String?) =
        RbacRepository.saveUserRules(userId, rules, operatorId)

    override suspend fun bindRole(userId: String, roleId: Long, operatorId: String?) =
        RbacRepository.bindRole(userId, roleId, operatorId)

    override suspend fun unbindRole(userId: String, operatorId: String?) =
        RbacRepository.unbindRole(userId, operatorId)
}
