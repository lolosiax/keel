package moe.lolosia.keel.classpath.full

/**
 * 插件 Classpath 的注册信息；只存在于 Host。
 *
 * @property keelPath `.keel` 包的绝对路径，不向插件暴露
 * @property resourcesPrefix `.keel` 内资源根，默认 `resources/`
 */
data class PluginClasspathEntry(
    val pluginId: String,
    val keelPath: String,
    val resourcesPrefix: String = "resources/",
    val version: String? = null,
    val sha256: String? = null,
)
