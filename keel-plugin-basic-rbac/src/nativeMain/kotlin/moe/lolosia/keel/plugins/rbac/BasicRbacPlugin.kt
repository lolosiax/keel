package moe.lolosia.keel.plugins.rbac

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.auth.Authorizer

/**
 * rbac 默认实现的生命周期入口。
 *
 * 契约（`RbacReader` / `RbacAdmin`）与 `RbacService` 由 KSP 登记为公开单例；
 * 这里只做两件事：启动时幂等检查表结构、把 [RbacAuthorizer] 交给 Host。
 *
 * `plugin.json` 声明 `provides: ["authz"]`：Host 在分发带 `@Permission` 的路由前调用它。
 * 换成第三方实现时，删除本插件的 `.keel`、放入对方的同名能力插件即可（同一 typeId）。
 */
@KeelPluginMain
class BasicRbacPlugin : Plugin {

    override fun onLoad(context: PluginContext) {
        RbacSchema.apply()
        Log.info("loaded rbac", tag = "RBAC")
    }

    override fun onEnable() {
        Log.info("rbac enabled", tag = "RBAC")
    }

    override fun authorizer(): Authorizer = RbacAuthorizer
}
