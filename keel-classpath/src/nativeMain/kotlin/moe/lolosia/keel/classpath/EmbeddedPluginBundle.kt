package moe.lolosia.keel.classpath

/** 是否存在内嵌的插件 bundle；只检查资源 / footer，不读取整包。 */
expect fun hasEmbeddedPluginBundle(executablePath: String): Boolean

/** 读取可执行文件内嵌的 `keel-bundle.zip`；没有时返回 null。 */
internal expect fun readEmbeddedPluginBundle(executablePath: String): ByteArray?

/**
 * 打开内嵌的插件 bundle。
 *
 * Windows 从 PE 资源 `IDR_KEEL_BUNDLE` 读取；Linux 从自身文件尾部的 trailer 读取。
 * 返回的 [Classpath] 位于内存中，不落盘。
 */
fun openEmbeddedPluginBundle(executablePath: String): Classpath? =
    readEmbeddedPluginBundle(executablePath)?.let { ZipClasspath.open(it) }
