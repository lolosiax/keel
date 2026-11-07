package moe.lolosia.keel.classpath.full

import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.ClasspathNotFoundException
import moe.lolosia.keel.classpath.ClasspathResource

/** 每次读取都校验代次；卸载后抛 [ClasspathNotFoundException]。 */
internal class GuardedClasspath(
    override val pluginId: String,
    override val generation: Long,
    private val isCurrent: () -> Boolean,
    private val delegate: Classpath,
) : ClasspathRef {

    override val entries: List<String>
        get() = check { delegate.entries }

    override fun read(path: String): ByteArray? =
        check(path) { delegate.read(path) }

    override fun open(path: String): ClasspathResource? =
        check(path) { delegate.open(path) }?.let { resource ->
            GuardedResource(pluginId, generation, isCurrent, resource)
        }

    override fun list(prefix: String): List<String> =
        check { delegate.list(prefix) }

    override fun exists(path: String): Boolean =
        check(path) { delegate.exists(path) }

    override fun lastModified(path: String): Long? =
        check(path) { delegate.lastModified(path) }

    override val scheme: String get() = delegate.scheme

    override val origin: String get() = "keel://$pluginId"

    private inline fun <T> check(path: String? = null, block: () -> T): T {
        if (!isCurrent()) throw ClasspathNotFoundException(pluginId, path)
        return block()
    }
}

private class GuardedResource(
    private val pluginId: String,
    private val generation: Long,
    private val isCurrent: () -> Boolean,
    private val delegate: ClasspathResource,
) : ClasspathResource {

    override val path: String get() = delegate.path

    override val size: Long get() = delegate.size

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isCurrent()) throw ClasspathNotFoundException(pluginId, path)
        return delegate.read(buffer, offset, length)
    }

    override fun close() {
        delegate.close()
    }
}
