package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath

/**
 * 插件 Classpath 的注册信息；只存在于 Host。
 *
 * @property keelPath `.keel` 包的绝对路径或内嵌来源标识，不向插件暴露
 * @property resourcesPrefix `.keel` 内资源根，默认 `resources/`
 * @property classpath 已打开的 Classpath；内嵌 bundle 直接给内存视图，磁盘包可以留空
 */
data class PluginClasspathEntry(
    val pluginId: String,
    val keelPath: String,
    val resourcesPrefix: String = "resources/",
    val version: String? = null,
    val sha256: String? = null,
    val classpath: Classpath? = null,
)
