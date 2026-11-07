package moe.lolosia.keel.classpath

/**
 * 给下层 Classpath 加固定前缀视图。
 *
 * 用于 `.keel` 包：zip 里的资源位于 `resources/`，业务代码仍然用
 * `static/home/index.html` 这种路径访问。
 */
class PrefixedClasspath(
    private val delegate: Classpath,
    prefix: String,
) : Classpath {

    private val prefix: String = normalizeClasspathPath(prefix).trim('/').let {
        if (it.isEmpty()) "" else "$it/"
    }

    override val entries: List<String>
        get() = delegate.entries
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }

    override fun read(path: String): ByteArray? {
        val rel = resolve(path) ?: return null
        return delegate.read(rel)
    }

    override fun open(path: String): ClasspathResource? {
        val rel = resolve(path) ?: return null
        return delegate.open(rel)
    }

    override fun list(prefix: String): List<String> {
        if (hasParentSegment(prefix)) return emptyList()
        val p = normalizeClasspathPath(prefix)
        return entries.filter { p.isEmpty() || it.startsWith(p) }
    }

    override fun exists(path: String): Boolean {
        val rel = resolve(path) ?: return false
        return delegate.exists(rel)
    }

    override fun lastModified(path: String): Long? {
        val rel = resolve(path) ?: return null
        return delegate.lastModified(rel)
    }

    override val scheme: String get() = delegate.scheme

    override val origin: String get() = delegate.origin

    private fun resolve(path: String): String? {
        if (prefix.isEmpty()) return normalizeClasspathPath(path)
        if (hasParentSegment(path)) return null
        val rel = normalizeClasspathPath(path).trimStart('/')
        if (rel.isEmpty()) return null
        return prefix + rel
    }
}
