// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz.api.service

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.plugins.basic.biz.api.service.AccountAdmin` 在消费方运行时里的代理。 */
class AccountAdminProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.basic.biz.api.service.AccountAdmin {

    override suspend fun register(req: moe.lolosia.keel.plugins.basic.biz.api.dto.RegisterReq): moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.UserRow>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.AccountAdmin", "register", ServiceWire.encode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.RegisterReq>(), req)))
    }

    override suspend fun createSession(userId: String): moe.lolosia.keel.plugins.basic.biz.api.dto.SessionToken {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.SessionToken>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.AccountAdmin", "createSession", ServiceWire.encode(serializer<String>(), userId)))
    }

    override suspend fun revokeSession(token: String): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.AccountAdmin", "revokeSession", ServiceWire.encode(serializer<String>(), token))
    }
}
