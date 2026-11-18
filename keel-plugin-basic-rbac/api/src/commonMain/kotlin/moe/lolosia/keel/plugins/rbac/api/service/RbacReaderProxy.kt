// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.rbac.api.service

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.plugins.rbac.api.service.RbacReader` 在消费方运行时里的代理。 */
class RbacReaderProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.rbac.api.service.RbacReader {

    override suspend fun roleOf(userId: String): moe.lolosia.keel.plugins.rbac.api.dto.RoleRef? {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.rbac.api.dto.RoleRef?>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "roleOf", ServiceWire.encode(serializer<String>(), userId)))
    }

    override suspend fun rolesOf(userId: String): kotlin.collections.List<moe.lolosia.keel.plugins.rbac.api.dto.RoleRef> {
        return ServiceWire.decode(serializer<kotlin.collections.List<moe.lolosia.keel.plugins.rbac.api.dto.RoleRef>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "rolesOf", ServiceWire.encode(serializer<String>(), userId)))
    }

    override suspend fun roleByType(type: String): moe.lolosia.keel.plugins.rbac.api.dto.RoleRef? {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.rbac.api.dto.RoleRef?>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "roleByType", ServiceWire.encode(serializer<String>(), type)))
    }

    override suspend fun effectiveRules(userId: String): kotlin.collections.List<String> {
        return ServiceWire.decode(serializer<kotlin.collections.List<String>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "effectiveRules", ServiceWire.encode(serializer<String>(), userId)))
    }

    override suspend fun rulesOfRole(roleId: Long): kotlin.collections.List<String> {
        return ServiceWire.decode(serializer<kotlin.collections.List<String>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "rulesOfRole", ServiceWire.encode(serializer<Long>(), roleId)))
    }

    override suspend fun rulesOfUser(userId: String): kotlin.collections.List<String> {
        return ServiceWire.decode(serializer<kotlin.collections.List<String>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "rulesOfUser", ServiceWire.encode(serializer<String>(), userId)))
    }

    override suspend fun listRoles(): kotlin.collections.List<moe.lolosia.keel.plugins.rbac.api.dto.RoleRef> {
        return ServiceWire.decode(serializer<kotlin.collections.List<moe.lolosia.keel.plugins.rbac.api.dto.RoleRef>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "listRoles", ByteArray(0)))
    }

    override suspend fun isOwner(userId: String): Boolean {
        return ServiceWire.decode(serializer<Boolean>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.rbac.api.service.RbacReader", "isOwner", ServiceWire.encode(serializer<String>(), userId)))
    }
}
