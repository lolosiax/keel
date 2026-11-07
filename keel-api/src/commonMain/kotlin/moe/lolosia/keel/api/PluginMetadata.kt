package moe.lolosia.keel.api

/**
 * 插件元信息，来自部署单元（插件目录 / 打包进 launcher 的清单），不来自类路径扫描。
 *
 * @property id 全局唯一标识，建议使用小写点分或短横线命名（如 `hello`、`system.users`）
 * @property version 插件版本
 * @property entry 插件 **Worker 线程内部**的工厂符号名（例如 `keel_plugin_hello_create`）；
 *   Host 只调用 `keel_plugin_bootstrap`，再由 bootstrap 在自己的线程上调用它
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val entry: String,
    val description: String = "",
    val authors: List<String> = emptyList(),
)
