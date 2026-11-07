package moe.lolosia.keel.host.route

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.web.RouteDescriptor

/** 一条命中的路由。 */
internal class RouteMatch(
    val pluginId: ULong,
    val routeId: String,
    val pathParameters: Map<String, String>,
)

/**
 * Host 侧路由表。
 *
 * 注册发生在启动阶段（泵线程），HTTP 开始服务后表不再变化，因此读侧无需加锁。
 * 若以后支持热注册 / 注销，必须改成不可变快照或加同步。
 */
internal object HostRoutes {

    private class Entry(val pluginId: ULong, val descriptor: RouteDescriptor)

    private val entries = mutableListOf<Entry>()

    fun register(pluginId: ULong, descriptors: List<RouteDescriptor>) {
        entries.removeAll { it.pluginId == pluginId }
        entries += descriptors.map { Entry(pluginId, it) }
        Log.info("registered ${descriptors.size} route(s) for plugin $pluginId", tag = "Router")
    }

    /** 线性匹配：精确段 > `{name}` 段；DEMO 规模足够。 */
    fun match(method: String, path: String): RouteMatch? {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }

        entries.forEach entryLoop@ { entry ->
            if (!entry.descriptor.method.equals(method, ignoreCase = true)) return@entryLoop

            val pattern = entry.descriptor.path.trim('/').split('/').filter { it.isNotEmpty() }
            if (pattern.size != parts.size) return@entryLoop

            val params = mutableMapOf<String, String>()
            pattern.forEachIndexed { index, segment ->
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    params[segment.substring(1, segment.length - 1)] = parts[index]
                } else if (segment != parts[index]) {
                    return@entryLoop
                }
            }
            return RouteMatch(entry.pluginId, entry.descriptor.routeId, params)
        }
        return null
    }
}
