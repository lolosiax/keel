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

    /**
     * 线性匹配：精确段 > `{name}` 段 > 末尾 `{...}` / `{name...}` 通配。
     *
     * 通配只允许出现在最后一段，匹配剩余的全部路径（可以为空）；参数值是**未解码的原始段**
     * 用 `/` 拼回去，WebDAV 这类协议自己决定怎么解码，Host 不替它做路径归一化。
     */
    fun match(method: String, path: String): RouteMatch? {
        val snapshot = entries
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }

        snapshot.forEach entryLoop@ { entry ->
            if (!entry.descriptor.method.equals(method, ignoreCase = true)) return@entryLoop

            val pattern = entry.descriptor.path.trim('/').split('/').filter { it.isNotEmpty() }
            val params = mutableMapOf<String, String>()

            for (index in pattern.indices) {
                val segment = pattern[index]
                if (segment == "{...}" || (segment.startsWith("{") && segment.endsWith("...}"))) {
                    val name = if (segment == "{...}") {
                        "..."
                    } else {
                        segment.substring(1, segment.length - 4)
                    }
                    params[name] = parts.drop(index).joinToString("/")
                    return RouteMatch(
                        pluginId = entry.pluginId,
                        routeId = entry.descriptor.routeId,
                        pathParameters = params,
                        permissions = entry.descriptor.permissions,
                    )
                }

                if (index >= parts.size) return@entryLoop
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    params[segment.substring(1, segment.length - 1)] = parts[index]
                } else if (segment != parts[index]) {
                    return@entryLoop
                }
            }

            if (pattern.size != parts.size) return@entryLoop
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
