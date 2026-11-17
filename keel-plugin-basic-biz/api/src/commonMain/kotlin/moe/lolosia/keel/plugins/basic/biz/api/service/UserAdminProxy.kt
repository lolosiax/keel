// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz.api.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

@Serializable
internal class UserAdminCallCreateArgs(
    val req: moe.lolosia.keel.plugins.basic.biz.api.dto.UserCreateReq,
    val operatorId: String?
)


@Serializable
internal class UserAdminCallUpdateArgs(
    val req: moe.lolosia.keel.plugins.basic.biz.api.dto.UserUpdateReq,
    val operatorId: String?
)


@Serializable
internal class UserAdminCallDeleteArgs(
    val ids: kotlin.collections.List<String>,
    val operatorId: String?
)


@Serializable
internal class UserAdminCallChangePasswordArgs(
    val userId: String,
    val origin: String,
    val target: String
)


@Serializable
internal class UserAdminCallSaveAvatarArgs(
    val userId: String,
    val fileName: String?,
    val bytes: ByteArray,
    val operatorId: String?
)


@Serializable
internal class UserAdminCallBindRoleArgs(
    val userId: String,
    val roleId: Long,
    val operatorId: String?
)


/** `moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin` 在消费方运行时里的代理。 */
class UserAdminProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin {

    override suspend fun create(req: moe.lolosia.keel.plugins.basic.biz.api.dto.UserCreateReq, operatorId: String?): moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin", "create", ServiceWire.encode(serializer<UserAdminCallCreateArgs>(), UserAdminCallCreateArgs(req = req, operatorId = operatorId))))
    }

    override suspend fun update(req: moe.lolosia.keel.plugins.basic.biz.api.dto.UserUpdateReq, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin", "update", ServiceWire.encode(serializer<UserAdminCallUpdateArgs>(), UserAdminCallUpdateArgs(req = req, operatorId = operatorId)))
    }

    override suspend fun delete(ids: kotlin.collections.List<String>, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin", "delete", ServiceWire.encode(serializer<UserAdminCallDeleteArgs>(), UserAdminCallDeleteArgs(ids = ids, operatorId = operatorId)))
    }

    override suspend fun changePassword(userId: String, origin: String, target: String): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin", "changePassword", ServiceWire.encode(serializer<UserAdminCallChangePasswordArgs>(), UserAdminCallChangePasswordArgs(userId = userId, origin = origin, target = target)))
    }

    override suspend fun saveAvatar(userId: String, fileName: String?, bytes: ByteArray, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin", "saveAvatar", ServiceWire.encode(serializer<UserAdminCallSaveAvatarArgs>(), UserAdminCallSaveAvatarArgs(userId = userId, fileName = fileName, bytes = bytes, operatorId = operatorId)))
    }

    override suspend fun bindRole(userId: String, roleId: Long, operatorId: String?): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin", "bindRole", ServiceWire.encode(serializer<UserAdminCallBindRoleArgs>(), UserAdminCallBindRoleArgs(userId = userId, roleId = roleId, operatorId = operatorId)))
    }
}
