package moe.lolosia.keel.api.auth

/**
 * 授权判定 SPI，由 Host 持有，**实现来自 keel-plugin-rbac**（或用户自己的授权插件）。
 *
 * - 路由带 `@Permission` 时，Host 在分发前调用它；
 * - 插件的 `ctx.hasPermission(...)` 也走同一个实例；
 * - 没有注册 Authorizer 时，带权限的路由一律 **fail closed**（503），不放行。
 *
 * 只传 `userId` 而不是角色 / 权限表：实现方自己决定查库、缓存或外部策略，
 * 避免把某一种 RBAC 模型写进 core。
 */
interface Authorizer {

    fun authorize(userId: String?, permission: String): Boolean
}
