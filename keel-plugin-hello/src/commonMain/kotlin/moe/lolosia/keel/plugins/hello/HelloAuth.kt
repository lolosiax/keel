package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.auth.*
import moe.lolosia.keel.api.web.UserSnapshot

/**
 * 认证 / 授权 SPI 的桩实现，用来跑通 Host 中间件的 200 / 401 / 403。
 *
 * - token `hello`：普通用户，只有 `hello.greeting` 权限；
 * - token `guest`：有效用户但没有任何权限（403）；
 * - token `owner`：超级管理员，忽略 `-` 规则；
 * - 其它 token：无效（401）。
 */
object HelloAuth : Authenticator, Authorizer {

    override suspend fun authenticate(token: String): UserSnapshot? = when (token) {
        "hello" -> UserSnapshot(id = "hello", username = "hello", roles = listOf("user"))
        "guest" -> UserSnapshot(id = "guest", username = "guest")
        "owner" -> UserSnapshot(id = "owner", username = "owner")
        else -> null
    }

    override suspend fun snapshot(userId: String): AuthzSnapshot = when (userId) {
        "owner" -> AuthzSnapshot(owner = true)
        "hello" -> AuthzSnapshot(roles = listOf("user"), rules = listOf("hello.greeting"))
        else -> AuthzSnapshot()
    }
}
