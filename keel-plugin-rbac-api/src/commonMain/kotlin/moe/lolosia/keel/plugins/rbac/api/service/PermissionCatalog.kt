package moe.lolosia.keel.plugins.rbac.api.service

import moe.lolosia.keel.api.annotation.*
import moe.lolosia.keel.api.web.*

/**
 * 权限节点目录。
 *
 * 节点由各插件的 `@Permission` 在构建期扫出，Host 聚合成一份；同一节点被多个插件声明是
 * 允许的（OR 语义），标题冲突取首个并记 WARN。管理界面的权限选择器从这里取数据。
 */
@ModuleService("rbac.catalog")
interface PermissionCatalog {

    /** 当前已启用插件声明的全部节点，按节点名排序。 */
    suspend fun nodes(): List<PermissionNode>
}
