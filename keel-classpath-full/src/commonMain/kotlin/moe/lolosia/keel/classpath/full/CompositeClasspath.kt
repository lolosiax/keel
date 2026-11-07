package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.ClasspathNotFoundException
import moe.lolosia.keel.classpath.ClasspathResource

/**
 * 先查 [primary]，缺失时回落到 [fallback]。
 *
 * 用于插件自己的 classpath：自身资源优先，缺失时读 Host。
 * 如果 [primary] 因为卸载而抛 [ClasspathNotFoundException]，不会回落到 Host。
 *
 * @param lookup 跨插件查询；为空时 [of] 抛 [ClasspathNotFoundException]
 */
class CompositeClasspath(
    private val primary: Classpath,
    private val fallback: Classpath,
    private val lookup: ((String) -> Classpath)? = null,
) : Classpath {

    override val entries: List<String>
        get() = (primary.entries + fallback.entries).distinct().sorted()

    override fun read(path: String): ByteArray? =
        primary.read(path) ?: fallback.read(path)

    override fun open(path: String): ClasspathResource? =
        primary.open(path) ?: fallback.open(path)

    override fun list(prefix: String): List<String> =
        (primary.list(prefix) + fallback.list(prefix)).distinct().sorted()

    override fun exists(path: String): Boolean =
        primary.exists(path) || fallback.exists(path)

    override fun lastModified(path: String): Long? =
        primary.lastModified(path) ?: fallback.lastModified(path)

    override val scheme: String get() = primary.scheme

    override val origin: String get() = primary.origin

    override fun of(pluginId: String): Classpath =
        lookup?.invoke(pluginId) ?: throw ClasspathNotFoundException(pluginId)
}
