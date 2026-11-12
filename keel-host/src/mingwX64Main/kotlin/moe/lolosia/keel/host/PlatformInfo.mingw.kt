package moe.lolosia.keel.host

import kotlinx.cinterop.*
import platform.windows.*

actual fun formatLocalTimestamp(): String = memScoped {
    val time = alloc<SYSTEMTIME>()
    GetLocalTime(time.ptr)
    buildString {
        append(time.wHour.toString().padStart(2, '0'))
        append(':')
        append(time.wMinute.toString().padStart(2, '0'))
        append(':')
        append(time.wSecond.toString().padStart(2, '0'))
    }
}

actual fun currentTimeMillis(): Long = memScoped {
    val fileTime = alloc<FILETIME>()
    GetSystemTimeAsFileTime(fileTime.ptr)
    val ticks = (fileTime.dwHighDateTime.toLong() shl 32) or fileTime.dwLowDateTime.toLong()
    // FILETIME 是 100ns 计数，起点 1601-01-01；换成 Unix epoch 毫秒
    ticks / 10_000L - 11_644_473_600_000L
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
