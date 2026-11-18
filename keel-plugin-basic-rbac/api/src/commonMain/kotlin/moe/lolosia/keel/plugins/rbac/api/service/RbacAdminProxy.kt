// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.rbac.api.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

@Serializable
internal class RbacAdminCallPageRolesArgs(
    val pageNo: Int,
    val pageSize: Int,
    val keyword: String?
)


@Serializable
internal class RbacAdminCallCreateRoleArgs(
    val req: moe.lolosia.keel.plugins.rbac.api.dto.RoleCreateReq,
    val operatorId: String?
)


@Serializable
internal class RbacAdminCallUpdateRoleArgs(
    val req: moe.lolosia.keel.plugins.rbac.api.dto.RoleUpdateReq,
    val operatorId: String?
)


@Serializable
internal class RbacAdminCallDeleteRoleArgs(
    val id: Long,
    val operatorId: String?
)


@Serializable
internal class RbacAdminCallSaveRoleRulesArgs(
    val roleId: Long,
    val rules: kotlin.collections.List<String>,
    val operatorId: String?
)


@Serializable
internal class RbacAdminCallSaveUserRulesArgs(
    val userId: String,
    val rules: kotlin.collections.List<String>,
    val operatorId: String?
)


@Serializable
internal class RbacAdminCallBindRoleArgs(
    val userId: String,
    val roleId: Long,
    val operatorId: String?
)


@Serializable
internal class RbacAdminCallUnbindRoleArgs(
    val userId: String,
    val operatorId: String?
)


/** `moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin` 在消费方运行时里的代理。 */
class RbacAdminProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin {

    override suspend fun pageRoles(pageNo: Int, pageSize: Int, keyword: String?): moe.lolosia.keel.plugins.rbac.api.dto.RolePage {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.rbac.api.dto.RolePage>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "pageRoles", ServiceWire.encode(serializer<RbacAdminCallPageRolesArgs>(), RbacAdminCallPageRolesArgs(pageNo = pageNo, pageSize = pageSize, keyword = keyword))))
    }

    override suspend fun createRole(req: moe.lolosia.keel.plugins.rbac.api.dto.RoleCreateReq, operatorId: String?): moe.lolosia.keel.plugins.rbac.api.dto.RoleRow {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.rbac.api.dto.RoleRow>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "createRole", ServiceWire.encode(serializer<RbacAdminCallCreateRoleArgs>(), RbacAdminCallCreateRoleArgs(req = req, operatorId = operatorId))))
    }

    override suspend fun updateRole(req: moe.lolosia.keel.plugins.rbac.api.dto.RoleUpdateReq, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "updateRole", ServiceWire.encode(serializer<RbacAdminCallUpdateRoleArgs>(), RbacAdminCallUpdateRoleArgs(req = req, operatorId = operatorId)))
    }

    override suspend fun deleteRole(id: Long, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "deleteRole", ServiceWire.encode(serializer<RbacAdminCallDeleteRoleArgs>(), RbacAdminCallDeleteRoleArgs(id = id, operatorId = operatorId)))
    }

    override suspend fun saveRoleRules(roleId: Long, rules: kotlin.collections.List<String>, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "saveRoleRules", ServiceWire.encode(serializer<RbacAdminCallSaveRoleRulesArgs>(), RbacAdminCallSaveRoleRulesArgs(roleId = roleId, rules = rules, operatorId = operatorId)))
    }

    override suspend fun saveUserRules(userId: String, rules: kotlin.collections.List<String>, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "saveUserRules", ServiceWire.encode(serializer<RbacAdminCallSaveUserRulesArgs>(), RbacAdminCallSaveUserRulesArgs(userId = userId, rules = rules, operatorId = operatorId)))
    }

    override suspend fun bindRole(userId: String, roleId: Long, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "bindRole", ServiceWire.encode(serializer<RbacAdminCallBindRoleArgs>(), RbacAdminCallBindRoleArgs(userId = userId, roleId = roleId, operatorId = operatorId)))
    }

    override suspend fun unbindRole(userId: String, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin", "unbindRole", ServiceWire.encode(serializer<RbacAdminCallUnbindRoleArgs>(), RbacAdminCallUnbindRoleArgs(userId = userId, operatorId = operatorId)))
    }
}
