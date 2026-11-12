package moe.lolosia.keel.host

import kotlinx.cinterop.*
import platform.posix.*

actual fun formatLocalTimestamp(): String = memScoped {
    val seconds = alloc<time_tVar>()
    time(seconds.ptr)
    val local = localtime(seconds.ptr) ?: return@memScoped "?"
    val buffer = allocArray<ByteVar>(16)
    strftime(buffer, 16uL, "%H:%M:%S".cstr.ptr, local)
    buffer.toKString()
}

actual fun currentTimeMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000L
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
