package moe.lolosia.keel.host.classpath

import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.full.*

/**
 * Host 侧 Classpath 入口。
 *
 * - [serverClasspath] 给 `KeelServer.classpath`：读 Host 资源，`of(id)` 访问插件；
 * - [plugin] 给 Host 内部按 id 取插件资源；
 * - 插件的 `.keel` 真实路径只保存在注册表里，不通过任何 getter 暴露。
 */
class HostClasspath(host: Classpath) {

    private val delegate: ClasspathRegistry = DefaultClasspathRegistry(host)

    val registry: ClasspathRegistry get() = delegate

    val serverClasspath: Classpath get() = RegistryClasspath(delegate)

    /** Host 侧事件泵使用：把 `CLASSPATH_READ` 请求落到注册表。 */
    fun transport(): ClasspathTransport = HostClasspathTransport(delegate)

    fun register(entry: PluginClasspathEntry): ClasspathRef = delegate.register(entry)

    fun unregister(pluginId: String) {
        delegate.unregister(pluginId)
    }

    fun plugin(pluginId: String): Classpath = delegate.require(pluginId)

    fun isLoaded(pluginId: String): Boolean = delegate.isLoaded(pluginId)
}
