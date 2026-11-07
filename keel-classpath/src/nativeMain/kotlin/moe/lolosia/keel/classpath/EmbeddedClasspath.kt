package moe.lolosia.keel.classpath

import kotlinx.cinterop.toKString
import platform.posix.getenv

/** 各目标实现：取出可执行文件内嵌的 `classpath.zip`；未嵌入时返回 `null`。 */
internal expect fun embeddedClasspathImage(): EmbeddedClasspathImage?

/**
 * 安装当前二进制的内嵌 classpath。
 *
 * 设置了 [envDir]（默认 `KEEL_CLASSPATH_DIR`）时读磁盘目录，否则读内嵌 zip。
 */
fun installEmbeddedClasspath(envDir: String = "KEEL_CLASSPATH_DIR"): Classpath {
    val diskRoot = getenv(envDir)?.toKString()
    val installed: Classpath = if (!diskRoot.isNullOrBlank()) {
        DiskClasspath(diskRoot)
    } else {
        embeddedClasspathImage()?.let { ZipClasspath.open(it.pointer, it.size) } ?: EmptyClasspath
    }
    ClasspathHolder.install(installed)
    return installed
}
