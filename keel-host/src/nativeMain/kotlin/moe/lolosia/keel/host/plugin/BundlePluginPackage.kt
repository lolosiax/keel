package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.PrefixedClasspath

/**
 * 内嵌 bundle 里的一个插件条目。
 *
 * bundle 是内存 zip，路径固定为 `plugins/<id>/...`；[root] 是该插件在 bundle 内的根。
 */
internal class BundlePluginPackage(
    bundle: Classpath,
    private val root: String,
    override val manifest: KeelPackageManifest,
) : PluginPackage {

    private val scoped: Classpath = PrefixedClasspath(bundle, root)

    override val origin: String = "embedded://$root"

    override val classpath: Classpath = PrefixedClasspath(scoped, manifest.resources)

    override val bundled: Boolean get() = true

    override fun read(path: String): ByteArray? = scoped.read(path)
}
