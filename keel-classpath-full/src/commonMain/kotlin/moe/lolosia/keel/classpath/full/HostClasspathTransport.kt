package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath

/**
 * Host 侧传输实现：事件泵收到 `CLASSPATH_READ` 之类的请求后调用这里。
 *
 * `HOST_ID` 解析为 Host 自己的 classpath，其余按插件 id 查注册表。
 */
class HostClasspathTransport(
    private val registry: ClasspathRegistry,
) : ClasspathTransport {

    override fun read(pluginId: String, path: String): ByteArray? =
        resolve(pluginId).read(path)

    override fun exists(pluginId: String, path: String): Boolean =
        resolve(pluginId).exists(path)

    override fun list(pluginId: String, prefix: String): List<String> =
        resolve(pluginId).list(prefix)

    override fun lastModified(pluginId: String, path: String): Long? =
        resolve(pluginId).lastModified(path)

    private fun resolve(pluginId: String): Classpath =
        if (pluginId == RemoteClasspath.HOST_ID) registry.host else registry.require(pluginId)
}
