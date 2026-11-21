package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.COpaquePointer
import platform.windows.GetLastError
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryExW

/** 只从库所在目录与 System32 解析依赖，避免被 PATH / 工作目录里的同名 DLL 劫持。 */
private const val LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR = 0x00000100u
private const val LOAD_LIBRARY_SEARCH_SYSTEM32 = 0x00000800u

internal actual class NativeLibrary actual constructor(path: String) {

    // LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR 不认正斜杠：路径里混有 `/` 时依赖解析会
    // 退回系统目录，带同目录依赖（如 libmariadb.dll）的能力包会报 ERROR_MOD_NOT_FOUND。
    private val module = LoadLibraryExW(
        path.replace('/', '\\'),
        null,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR or LOAD_LIBRARY_SEARCH_SYSTEM32,
    ) ?: throw IllegalStateException("加载动态库失败：$path（Win32 ${GetLastError()}）")

    actual fun symbol(name: String): COpaquePointer? = GetProcAddress(module, name)
}
