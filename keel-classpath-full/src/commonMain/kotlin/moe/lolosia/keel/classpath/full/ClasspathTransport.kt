package moe.lolosia.keel.classpath.full

/**
 * 跨运行时 Classpath 读取的传输层。
 *
 * Host 侧实现直接用注册表；插件侧实现走事件泵 / C ABI。
 * 先只定义一次性字节读取，流式与分块在后续扩展。
 */
interface ClasspathTransport {

    fun read(pluginId: String, path: String): ByteArray?

    fun exists(pluginId: String, path: String): Boolean

    fun list(pluginId: String, prefix: String): List<String>

    fun lastModified(pluginId: String, path: String): Long?
}
