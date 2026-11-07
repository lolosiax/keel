package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.ByteArrayClasspathResource
import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.ClasspathNotFoundException
import moe.lolosia.keel.classpath.ClasspathResource

/**
 * 插件运行时的远程 Classpath 引用。
 *
 * 自身不持有 `.keel` 路径；所有读取都经 [transport] 到 Host。
 * `HOST_ID` 是保留命名空间，代表 Host 自己的 classpath。
 */
class RemoteClasspath(
    override val pluginId: String,
    private val transport: ClasspathTransport,
    override val generation: Long = 0,
    private val lookup: ((String) -> Classpath)? = null,
) : ClasspathRef {

    override val entries: List<String>
        get() = transport.list(pluginId, "")

    override fun read(path: String): ByteArray? =
        transport.read(pluginId, path)

    override fun open(path: String): ClasspathResource? =
        transport.read(pluginId, path)?.let { ByteArrayClasspathResource(path, it) }

    override fun list(prefix: String): List<String> =
        transport.list(pluginId, prefix)

    override fun exists(path: String): Boolean =
        transport.exists(pluginId, path)

    override fun lastModified(path: String): Long? =
        transport.lastModified(pluginId, path)

    override val scheme: String = "keel"

    override val origin: String get() = "keel://$pluginId"

    override fun of(pluginId: String): Classpath =
        lookup?.invoke(pluginId) ?: throw ClasspathNotFoundException(pluginId)

    companion object {
        /** 保留给 Host 自身 classpath 的命名空间。 */
        const val HOST_ID: String = "__host__"
    }
}

/**
 * 插件侧的常用组合：自己的 `.keel` 优先，缺失时读 Host。
 *
 * @param local 插件自己的本地 Classpath（通常由 bootstrap 用 `.keel` 路径创建）
 * @param transport 走 Host 的读取通道
 */
fun remotePluginClasspath(
    local: Classpath,
    transport: ClasspathTransport,
): Classpath = CompositeClasspath(
    primary = local,
    fallback = RemoteClasspath(RemoteClasspath.HOST_ID, transport),
    lookup = { RemoteClasspath(it, transport) },
)
