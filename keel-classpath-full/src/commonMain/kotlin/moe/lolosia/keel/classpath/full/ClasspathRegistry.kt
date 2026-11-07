package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.ClasspathNotFoundException

/**
 * 插件 Classpath 注册表，由 Host 持有。
 *
 * `host` 是 Host 自己的 classpath；插件命名空间通过 [register] / [unregister]
 * 管理。读取侧拿到的是 [ClasspathRef]，不暴露 `.keel` 路径。
 */
interface ClasspathRegistry {

    val host: Classpath

    /**
     * 注册（或替换）一个插件命名空间。
     *
     * 相同 `pluginId` 再次注册会让旧引用失效，返回新代次的引用。
     */
    fun register(entry: PluginClasspathEntry): ClasspathRef

    /** 卸载命名空间；之后 [get] 返回 null，旧引用读取抛 [ClasspathNotFoundException]。 */
    fun unregister(pluginId: String)

    fun get(pluginId: String): ClasspathRef?

    fun require(pluginId: String): ClasspathRef =
        get(pluginId) ?: throw ClasspathNotFoundException(pluginId)

    fun isLoaded(pluginId: String): Boolean = get(pluginId) != null
}

/**
 * 插件侧常用组合：自己的资源优先，缺失时回落 Host。
 *
 * 跨插件访问不经过这个组合，必须显式 `registry.require("other")`。
 */
fun pluginClasspath(registry: ClasspathRegistry, pluginId: String): Classpath =
    CompositeClasspath(
        primary = registry.require(pluginId),
        fallback = registry.host,
        lookup = { registry.require(it) },
    )
