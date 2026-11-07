package moe.lolosia.keel.host

import kotlinx.cinterop.*
import platform.posix.*

actual fun formatLocalTimestamp(): String = memScoped {
    val now = alloc<timeval>()
    gettimeofday(now.ptr, null)
    val seconds = alloc<time_tVar>()
    seconds.value = now.tv_sec
    val time = localtime(seconds.ptr) ?: return@memScoped "?"
    val buffer = allocArray<ByteVar>(32)
    strftime(buffer, 32uL, "%Y-%m-%d %H:%M:%S".cstr.ptr, time)
    val millis = (now.tv_usec / 1000).toString().padStart(3, '0')
    "${buffer.toKString()}.$millis"
}

actual fun currentWorkingDirectory(): String = memScoped {
    val buffer = allocArray<ByteVar>(4096)
    if (getcwd(buffer, 4096uL) == null) "?" else buffer.toKString()
}

actual fun executablePath(): String = memScoped {
    val buffer = allocArray<ByteVar>(4096)
    val length = readlink("/proc/self/exe".cstr.ptr, buffer, 4095uL)
    if (length <= 0) "?" else buffer.readBytes(length.toInt()).decodeToString()
}
