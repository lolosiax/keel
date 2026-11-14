package moe.lolosia.keel.api.debug

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Linux 侧 socket 实现：`fcntl` 切非阻塞、`close` 关闭。
 *
 * 只连 `127.0.0.1`；调试通道不离开本机。
 */
private const val INVALID_FD = -1L

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketInit(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketConnect(port: Int): Long {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return INVALID_FD

    val connected = memScoped {
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
        address.sin_family = AF_INET.convert()
        address.sin_port = htons(port.toUShort())
        address.sin_addr.s_addr = htonl(0x7F000001u).convert()
        connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
    }

    if (!connected) {
        close(fd)
        return INVALID_FD
    }
    return fd.toLong()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketSetNonBlocking(fd: Long): Boolean {
    val raw = fd.toInt()
    val flags = fcntl(raw, F_GETFL, 0)
    if (flags < 0) return false
    return fcntl(raw, F_SETFL, flags or O_NONBLOCK) == 0
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketRead(fd: Long, buffer: ByteArray, offset: Int, length: Int): Int {
    if (length <= 0) return -1
    val read = buffer.usePinned { recv(fd.toInt(), it.addressOf(offset), length.convert(), 0) }
    return if (read <= 0) -1 else read.toInt()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketWrite(fd: Long, buffer: ByteArray, offset: Int, length: Int): Boolean {
    var sent = 0
    while (sent < length) {
        val current = buffer.usePinned {
            send(fd.toInt(), it.addressOf(offset + sent), (length - sent).convert(), 0)
        }
        if (current <= 0) return false
        sent += current.toInt()
    }
    return true
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketClose(fd: Long) {
    if (fd >= 0) close(fd.toInt())
}

internal actual fun debugSleep(millis: Int) {
    usleep(millis.toUInt() * 1000u)
}
