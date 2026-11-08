package moe.lolosia.keel.host.route

import kotlin.concurrent.Volatile
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.web.PermissionNode

/**
 * Host 侧的权限节点目录：各插件注册路由时把自己的 `@Permission` 节点一起送上来，
 * 这里聚合成一份给管理界面（rbac 的 `/api/permission/catalog`）使用。
 *
 * 与 [HostRoutes] 相同的并发模型：写入只在泵线程，读取发生在 HTTP 线程，
 * 用不可变快照 + 原子替换。同一节点被多个插件声明是允许的（OR 语义），
 * 标题冲突取首个并记 WARN。
 */
internal object HostPermissionCatalog {

    private class Entry(val pluginId: ULong, val node: PermissionNode)

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun register(pluginId: ULong, nodes: List<PermissionNode>) {
        val kept = entries.filterNot { it.pluginId == pluginId }
        val existing = kept.mapTo(mutableSetOf()) { it.node.node }
        val added = nodes.filter { node ->
            val fresh = existing.add(node.node)
            if (!fresh) {
                val known = entries.firstOrNull { it.node.node == node.node }
                if (known != null && known.node.title != node.title) {
                    Log.warn(
                        "权限节点 ${node.node} 的标题冲突：保留「${known.node.title}」，忽略「${node.title}」",
                        tag = "Router",
                    )
                }
            }
            fresh
        }
        entries = kept + added.map { Entry(pluginId, it) }
        if (added.isNotEmpty()) {
            Log.info("registered ${added.size} permission node(s) for plugin $pluginId", tag = "Router")
        }
    }

    /** 插件停用 / 卸载时清掉它声明的节点。 */
    fun remove(pluginId: ULong) {
        val snapshot = entries
        val next = snapshot.filterNot { it.pluginId == pluginId }
        if (next.size == snapshot.size) return
        entries = next
        Log.info("unregistered ${snapshot.size - next.size} permission node(s) for plugin $pluginId", tag = "Router")
    }

    /** 聚合后的节点表，按节点名排序。 */
    fun snapshot(): List<PermissionNode> = entries.map { it.node }.sortedBy { it.node }
}
