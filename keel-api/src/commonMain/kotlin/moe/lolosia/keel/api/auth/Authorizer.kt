package moe.lolosia.keel.api.auth

import moe.lolosia.keel.api.web.Permissions

/**
 * 授权判定 SPI，由 Host 持有，**实现来自 keel-plugin-rbac**（或用户自己的授权插件）。
 *
 * - 路由带 `@Permission` 时，Host 在分发前调用 [authorize]；
 * - 插件的 `ctx.hasPermission(...)` 用已注入的 `UserSnapshot.rules` 本地判定，不走这里；
 * - 没有注册 Authorizer 时，带权限的路由一律 **fail closed**（503），不放行。
 *
 * 是 `suspend` 的：rbac 在独立运行时里，判定要经事件请求完成；Host 会按 TTL 缓存结果。
 */
interface Authorizer {

    /** 取用户的授权快照（组 inherit 展开后的规则）。 */
    suspend fun snapshot(userId: String): AuthzSnapshot

    /**
     * 默认实现：`owner` 直接放行，否则按规则串判定。
     *
     * 自定义授权模型（不能表达成规则串）可以覆盖本方法。
     */
    suspend fun authorize(userId: String?, permission: String): Boolean {
        if (userId == null) return false
        val snapshot = snapshot(userId)
        return snapshot.owner || Permissions.granted(snapshot.rules, permission)
    }
}
