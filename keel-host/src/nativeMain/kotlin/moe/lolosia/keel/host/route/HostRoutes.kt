package moe.lolosia.keel.host.route

import kotlin.concurrent.Volatile
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.web.RouteDescriptor

/** 一条命中的路由；[permissions] 是该路由声明的 `@Permission` 节点。 */
internal class RouteMatch(
    val pluginId: ULong,
    val routeId: String,
    val pathParameters: Map<String, String>,
    val permissions: List<String>,
)

/**
 * Host 侧路由表。
 *
 * 写入只发生在泵线程（启动注册、控制台启停 / 卸载），读取发生在 Ktor 线程，
 * 因此表本身是不可变快照：每次变更新建一份列表再原子替换，读者只取一次引用。
 * 单项 [Entry] 与其 [RouteDescriptor] 都不可变，快照内不需要额外同步。
 */
internal object HostRoutes {

    private class Entry(val pluginId: ULong, val descriptor: RouteDescriptor)

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun register(pluginId: ULong, descriptors: List<RouteDescriptor>) {
        entries = entries.filterNot { it.pluginId == pluginId } +
            descriptors.map { Entry(pluginId, it) }
        Log.info("registered ${descriptors.size} route(s) for plugin $pluginId", tag = "Router")
    }

    /** 插件停用 / 卸载时清掉它的全部路由。 */
    fun remove(pluginId: ULong) {
        val snapshot = entries
        val next = snapshot.filterNot { it.pluginId == pluginId }
        if (next.size == snapshot.size) return
        entries = next
        Log.info("unregistered ${snapshot.size - next.size} route(s) for plugin $pluginId", tag = "Router")
    }

    /** 线性匹配：精确段 > `{name}` 段；DEMO 规模足够。 */
    fun match(method: String, path: String): RouteMatch? {
        val snapshot = entries
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }

        snapshot.forEach entryLoop@ { entry ->
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
            return RouteMatch(
                pluginId = entry.pluginId,
                routeId = entry.descriptor.routeId,
                pathParameters = params,
                permissions = entry.descriptor.permissions,
            )
        }
        return null
    }
}
