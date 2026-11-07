package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.COpaquePointer
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryA

internal actual class NativeLibrary actual constructor(path: String) {

    private val module = LoadLibraryA(path)
        ?: throw IllegalStateException("加载动态库失败：$path")

    actual fun symbol(name: String): COpaquePointer? = GetProcAddress(module, name)
}
