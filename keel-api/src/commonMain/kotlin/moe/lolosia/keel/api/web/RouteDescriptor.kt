package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * 插件启用时注册给 Host 的一条路由。
 *
 * [path] 支持 `{name}` 段；[routeId] 在插件内部唯一，Host 分发时原样带回。
 */
@Serializable
data class RouteDescriptor(
    val method: String,
    val path: String,
    val routeId: String,
    val permissions: List<String> = emptyList(),
)
