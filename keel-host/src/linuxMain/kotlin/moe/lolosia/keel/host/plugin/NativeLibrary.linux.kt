package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.COpaquePointer
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

internal actual class NativeLibrary actual constructor(path: String) {

    private val handle = dlopen(path, RTLD_NOW)
        ?: throw IllegalStateException("加载动态库失败：$path")

    actual fun symbol(name: String): COpaquePointer? = dlsym(handle, name)
}
