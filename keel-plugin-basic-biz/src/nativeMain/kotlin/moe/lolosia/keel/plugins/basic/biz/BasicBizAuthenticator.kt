package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.auth.Authenticator
import moe.lolosia.keel.api.web.UserSnapshot
import moe.lolosia.keel.plugins.basic.biz.db.AccountRepository
import moe.lolosia.keel.plugins.basic.biz.db.UserRepository
import moe.lolosia.keel.plugins.rbac.api.ROLE_TYPE_OWNER

/**
 * Host 认证 SPI：`Authorization: Bearer <会话 id>` → [UserSnapshot]。
 *
 * 只解析「会话 → 用户 → 组 type」；权限规则由 rbac 插件在 Host 的 `Authorizer` 里合并，
 * 这里不越界读 `sys_role_permission`。
 */
internal object BasicBizAuthenticator : Authenticator {

    override suspend fun authenticate(token: String): UserSnapshot? {
        if (token.isBlank()) return null
        val userId = AccountRepository.userIdOf(token) ?: return null
        val user = UserRepository.findById(userId) ?: return null
        val roles = UserRepository.roleTypes(userId)
        return UserSnapshot(
            id = user.id,
            username = user.userName,
            roles = roles,
            owner = roles.any { it == ROLE_TYPE_OWNER },
        )
    }
}
