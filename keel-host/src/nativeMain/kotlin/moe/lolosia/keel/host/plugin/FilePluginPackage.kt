package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.FileZipClasspath
import moe.lolosia.keel.classpath.PrefixedClasspath

/**
 * 磁盘上的 `.keel` 包。
 *
 * 按文件随机读，不整包载入；[classpath] 只是加了 `resources/` 前缀的视图。
 */
internal class FilePluginPackage(
    private val keelPath: String,
    override val manifest: KeelPackageManifest,
) : PluginPackage {

    private val zip: Classpath = FileZipClasspath.open(keelPath)

    override val origin: String get() = keelPath

    override val classpath: Classpath = PrefixedClasspath(zip, manifest.resources)

    override val bundled: Boolean get() = false

    override fun read(path: String): ByteArray? = zip.read(path)
}
