package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath

/**
 * Host 自己的 classpath 视图：读取 Host 资源，同时支持 `of(pluginId)`
 * 访问插件命名空间。
 *
 * 只传给 Host 侧的 `KeelServer.classpath`；插件拿到的是
 * [pluginClasspath] 组合或跨运行时代理。
 */
class RegistryClasspath(
    private val registry: ClasspathRegistry,
) : Classpath by registry.host {

    override fun of(pluginId: String): Classpath = registry.require(pluginId)
}
