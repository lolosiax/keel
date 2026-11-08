package moe.lolosia.keel.host.service

import kotlin.concurrent.Volatile
import moe.lolosia.keel.api.Log

/**
 * Host 侧的服务目录：`service id → plugin id`。
 *
 * 只记录声明，不理解方法签名；写入只在泵线程（启用 / 停用），读取也在泵线程
 * （转发 SERVICE_CALL 时），因此无需加锁。同一服务被两个插件声明时保留先到的，
 * 后者告警——与「一个 SPI 只允许一个提供者」同一条规则。
 */
internal object HostServices {

    private class Entry(val pluginId: ULong, val serviceId: String)

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun register(pluginId: ULong, services: List<String>) {
        val declared = services.distinct()
        if (declared.isEmpty()) {
            remove(pluginId)
            return
        }

        val kept = entries.filterNot { it.pluginId == pluginId }
        val owners = kept.associateBy { it.serviceId }
        val accepted = declared.mapNotNull { serviceId ->
            val owner = owners[serviceId]
            if (owner != null && owner.pluginId != pluginId) {
                Log.warn(
                    "服务 $serviceId 已被插件 ${owner.pluginId} 占用，忽略插件 $pluginId 的声明",
                    tag = "Service",
                )
                null
            } else {
                Entry(pluginId, serviceId)
            }
        }
        entries = kept + accepted
        Log.info("registered ${accepted.size} service(s) for plugin $pluginId", tag = "Service")
    }

    /** 插件停用 / 卸载时清掉它声明的服务。 */
    fun remove(pluginId: ULong) {
        val snapshot = entries
        val next = snapshot.filterNot { it.pluginId == pluginId }
        if (next.size == snapshot.size) return
        entries = next
        Log.info("unregistered ${snapshot.size - next.size} service(s) for plugin $pluginId", tag = "Service")
    }

    fun providerOf(serviceId: String): ULong? =
        entries.firstOrNull { it.serviceId == serviceId }?.pluginId
}
