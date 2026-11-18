// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.rbac.api.service

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.rbac.api.service.RbacAdmin` 的单例访问器；提供者未启用时抛异常。 */
val PluginContext.rbacAdmin: RbacAdmin
    get() {
        return publicApi.requireInstance(RbacAdminKey) { RbacAdminProxy(it) }
    }

/** 同 [rbacAdmin]；提供者未启用时返回 null（可替换能力按缺席降级时用它）。 */
val PluginContext.rbacAdminOrNull: RbacAdmin?
    get() {
        return publicApi.getInstance(RbacAdminKey) { RbacAdminProxy(it) }
    }
