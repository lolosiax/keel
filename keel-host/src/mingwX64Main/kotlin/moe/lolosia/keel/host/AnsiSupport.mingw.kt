package moe.lolosia.keel.host

import kotlinx.cinterop.*
import platform.posix.getenv
import platform.windows.*

/** `STD_OUTPUT_HANDLE` 在 mingw 头文件里不一定导出，自己按 Win32 约定写。 */
private const val KEEL_STD_OUTPUT_HANDLE = 0xFFFFFFF5u

private const val KEEL_ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004u

actual fun ansiSupported(): Boolean {
    if (getenv("NO_COLOR") != null) return false
    if (getenv("CLICOLOR")?.toKString() == "0") return false
    val force = getenv("FORCE_COLOR")?.toKString()
    if (force != null && force != "0") return true

    val handle = GetStdHandle(KEEL_STD_OUTPUT_HANDLE) ?: return false
    memScoped {
        val mode = alloc<DWORDVar>()
        if (GetConsoleMode(handle, mode.ptr) == 0) return false
        if ((mode.value and KEEL_ENABLE_VIRTUAL_TERMINAL_PROCESSING) == 0u) {
            val enabled = mode.value or KEEL_ENABLE_VIRTUAL_TERMINAL_PROCESSING
            if (SetConsoleMode(handle, enabled) == 0) return false
        }
    }
    return true
}
