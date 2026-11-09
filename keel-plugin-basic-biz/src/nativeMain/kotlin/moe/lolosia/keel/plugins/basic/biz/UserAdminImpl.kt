package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.ModuleServiceProvider
import moe.lolosia.keel.api.web.ApiException
import moe.lolosia.keel.plugins.basic.biz.api.dto.*
import moe.lolosia.keel.plugins.basic.biz.api.service.UserAdmin
import moe.lolosia.keel.plugins.basic.biz.db.AccountRepository
import moe.lolosia.keel.plugins.basic.biz.db.UserRepository
import moe.lolosia.keel.plugins.basic.biz.db.toRow

/**
 * [UserAdmin] 的实现：用户生命周期 + 头像落插件存储。
 *
 * 绑定表（`sys_user_roles`）的写归 basic-biz（见 basic-stack-design §5.3）；
 * 组定义与权限规则不在本插件里。
 */
@ModuleServiceProvider
class UserAdminImpl(private val context: PluginContext) : UserAdmin {

    override suspend fun create(req: UserCreateReq, operatorId: String?): UserRow =
        UserRepository.create(req, operatorId).toRow()

    override suspend fun update(req: UserUpdateReq, operatorId: String?) {
        UserRepository.update(req, operatorId)
    }

    override suspend fun delete(ids: List<String>, operatorId: String?) {
        UserRepository.softDelete(ids, operatorId)
        ids.forEach { AccountRepository.revokeByUser(it) }
    }

    override suspend fun changePassword(userId: String, origin: String, target: String) {
        UserRepository.changePassword(userId, origin, target)
    }

    override suspend fun saveAvatar(
        userId: String,
        fileName: String?,
        bytes: ByteArray,
        operatorId: String?,
    ) {
        if (bytes.isEmpty()) throw ApiException(400, "头像内容为空")
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it == "png" || it == "jpg" || it == "jpeg" }
            ?: "png"
        val stored = "$userId.$extension"
        context.server.storage.write("image/avatar/$stored", bytes)
        UserRepository.updateAvatar(userId, stored, operatorId)
    }

    override suspend fun bindRole(userId: String, roleId: Long, operatorId: String?) {
        UserRepository.bindRole(userId, roleId, operatorId)
    }

    /** 读头像；没有设置返回 null（控制器据此回 404）。 */
    suspend fun avatarOf(userId: String): Pair<String, ByteArray>? {
        val name = UserRepository.avatarName(userId)?.takeIf { it.isNotBlank() } ?: return null
        val bytes = context.server.storage.read("image/avatar/$name") ?: return null
        return name to bytes
    }
}
