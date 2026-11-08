package moe.lolosia.keel.api.web

/**
 * 由 `keel-router-ksp` 生成的插件路由集合。
 *
 * 每个插件生成一个实现，固定名 `KeelPluginRoutes`、与 `@KeelPluginMain` 同包；
 * 生成的 Worker 入口把它交给 `PluginWorker`，在 `onLoad` 之后统一注册并发布给 Host。
 *
 * 插件代码不直接引用这个接口；没有 `@Controller` 的插件也会生成空实现。
 */
interface GeneratedRoutes {

    /** 把生成的路由注册进插件的路由表。 */
    fun register(routes: Routes)

    /** 这些路由声明过的权限节点，随路由一起发给 Host 聚合。 */
    fun permissions(): List<PermissionNode>
}
