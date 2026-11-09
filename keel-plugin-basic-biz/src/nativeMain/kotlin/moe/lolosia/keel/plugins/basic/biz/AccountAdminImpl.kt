package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.ModuleServiceProvider
import moe.lolosia.keel.plugins.basic.biz.api.dto.*
import moe.lolosia.keel.plugins.basic.biz.api.service.AccountAdmin
import moe.lolosia.keel.plugins.basic.biz.db.AccountRepository

/** [AccountAdmin] 的实现：注册、建会话、吊销会话。 */
@ModuleServiceProvider
class AccountAdminImpl(private val context: PluginContext) : AccountAdmin {

    override suspend fun register(req: RegisterReq): UserRow = AuthService.register(req)

    override suspend fun createSession(userId: String): SessionToken =
        SessionToken(AccountRepository.create(userId, BasicBizSettings(context).load().sessionTtlSeconds))

    override suspend fun revokeSession(token: String) {
        AccountRepository.revoke(token)
    }
}
