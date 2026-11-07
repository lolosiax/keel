package moe.lolosia.keel.classpath

import platform.posix.dlsym

/**
 * Linux：从可执行文件自身的符号表取 `classpath.zip` 的起止地址。
 *
 * 构建期用 `objcopy -I binary` 把 zip 转成目标文件后链接进程序，产生
 * `_binary_classpath_zip_start/_end`；链接参数需要 `-Wl,--export-dynamic`。
 */
internal actual fun embeddedClasspathImage(): EmbeddedClasspathImage? {
    val start = dlsym(null, SYMBOL_START) ?: return null
    val end = dlsym(null, SYMBOL_END) ?: return null
    val size = (rawAddress(end) - rawAddress(start)).toInt()
    if (size <= 0) return null
    return EmbeddedClasspathImage(start, size)
}

@Suppress("USELESS_CAST")
private fun rawAddress(pointer: kotlinx.cinterop.COpaquePointer): Long = pointer.rawValue.toLong()

private const val SYMBOL_START = "_binary_classpath_zip_start"
private const val SYMBOL_END = "_binary_classpath_zip_end"
