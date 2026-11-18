package moe.lolosia.keel.plugins.rbac

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.auth.AuthzSnapshot
import moe.lolosia.keel.api.auth.Authorizer
import moe.lolosia.keel.plugins.rbac.api.ROLE_TYPE_OWNER
import moe.lolosia.keel.plugins.rbac.db.RbacRepository

/**
 * Host 的授权 SPI：把用户的组链规则合并成 [AuthzSnapshot]。
 *
 * Host 在分发带 `@Permission` 的路由前调用它，并把结果并进 `UserSnapshot`；
 * 插件侧的 `ctx.hasPermission` 因此是本地判定。
 *
 * 数据库不可达时不抛异常（否则整站 503），而是返回空快照：
 * 没有规则 → 未命中 → **拒绝**，保持 fail closed。
 */
internal object RbacAuthorizer : Authorizer {

    override suspend fun snapshot(userId: String): AuthzSnapshot = runCatching {
        val groups = RbacRepository.groupsOf(userId)
        AuthzSnapshot(
            roles = groups.mapNotNull { it.type },
            rules = RbacRepository.effectiveRules(userId),
            owner = groups.any { it.type?.lowercase() == ROLE_TYPE_OWNER },
        )
    }.getOrElse {
        Log.warn("授权快照读取失败，按空规则处理：${it.message}", tag = "RBAC")
        AuthzSnapshot()
    }
}
