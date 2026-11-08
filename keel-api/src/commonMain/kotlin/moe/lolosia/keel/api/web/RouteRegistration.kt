package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * 插件启用时发给 Host 的完整注册载荷。
 *
 * 路由与权限节点一次送达：Host 据此建路由表，并把节点并入权限目录，
 * 管理界面的权限选择器才能看到别的插件声明的节点。
 */
@Serializable
data class RouteRegistration(
    val routes: List<RouteDescriptor> = emptyList(),
    val permissions: List<PermissionNode> = emptyList(),
)
