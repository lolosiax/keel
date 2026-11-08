package moe.lolosia.keel.plugins.rbac.api

import moe.lolosia.keel.api.service.*
import moe.lolosia.keel.plugins.rbac.api.service.*

/**
 * rbac 服务的查找键。
 *
 * 跨模块只认 [ServiceKey.id]；在 `plugin.json` 里声明 `requires: ["rbac"]` 后，
 * 用 [reader] / [admin] / [catalog] 从 `ctx.modules` 取本地代理。
 */
object RbacServices {

    /** 判定侧只读接口。 */
    val reader: ServiceKey<RbacReader> = ServiceKey("rbac")

    /** 管理侧读写接口。 */
    val admin: ServiceKey<RbacAdmin> = ServiceKey("rbac.admin")

    /** 权限节点目录（由 Host 聚合各插件声明）。 */
    val catalog: ServiceKey<PermissionCatalog> = ServiceKey("rbac.catalog")
}
