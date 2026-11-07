package moe.lolosia.keel.host

import kotlinx.cinterop.*
import platform.windows.*

actual fun formatLocalTimestamp(): String = memScoped {
    val time = alloc<SYSTEMTIME>()
    GetLocalTime(time.ptr)
    buildString {
        append(time.wYear.toString().padStart(4, '0'))
        append('-')
        append(time.wMonth.toString().padStart(2, '0'))
        append('-')
        append(time.wDay.toString().padStart(2, '0'))
        append(' ')
        append(time.wHour.toString().padStart(2, '0'))
        append(':')
        append(time.wMinute.toString().padStart(2, '0'))
        append(':')
        append(time.wSecond.toString().padStart(2, '0'))
        append('.')
        append(time.wMilliseconds.toString().padStart(3, '0'))
    }
}

actual fun currentWorkingDirectory(): String = memScoped {
    val buffer = allocArray<UShortVar>(32768)
    val length = GetCurrentDirectoryW(32768u, buffer)
    if (length == 0u) "?" else buffer.toKStringFromUtf16()
}

actual fun executablePath(): String = memScoped {
    val buffer = allocArray<UShortVar>(32768)
    val length = GetModuleFileNameW(null, buffer, 32768u)
    if (length == 0u) "?" else buffer.toKStringFromUtf16()
}
