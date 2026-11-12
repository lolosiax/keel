package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.host.ClasspathNames
import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.ClasspathResource

/**
 * 插件自己的资源视图：自身 `.keel` 优先，缺失时回落 Host。
 *
 * `of(id)` 只查对方命名空间，不回落 Host。读取经反向请求，只能在 Worker 线程调用。
 */
internal class PluginClasspath : Classpath {

    private val own = HostClasspathClient(namespace = null)

    private val host = HostClasspathClient(ClasspathNames.HOST)

    override val entries: List<String>
        get() = (own.entries + host.entries).distinct().sorted()

    override fun read(path: String): ByteArray? = own.read(path) ?: host.read(path)

    override fun open(path: String): ClasspathResource? = own.open(path) ?: host.open(path)

    override fun list(prefix: String): List<String> =
        (own.list(prefix) + host.list(prefix)).distinct().sorted()

    override fun exists(path: String): Boolean = own.exists(path) || host.exists(path)

    override val scheme: String = "keel"

    override val origin: String get() = own.origin

    override fun of(pluginId: String): Classpath = HostClasspathClient(pluginId)
}
