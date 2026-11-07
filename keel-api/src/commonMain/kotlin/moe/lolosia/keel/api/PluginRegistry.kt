package moe.lolosia.keel.api

/**
 * 插件目录的只读视图（Bukkit `PluginManager` 的只读部分）。
 *
 * 加载 / 启用 / 禁用由 Host 的运维接口负责，不暴露给插件；
 * 插件之间只通过事件总线或服务注册表协作。
 */
interface PluginRegistry {

    val ids: List<String>

    fun metadata(id: String): PluginMetadata?

    fun isEnabled(id: String): Boolean
}
