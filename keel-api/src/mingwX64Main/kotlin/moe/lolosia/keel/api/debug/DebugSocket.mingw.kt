package moe.lolosia.keel.api.debug

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Windows 侧 socket 实现：`WSAStartup` 初始化、`ioctlsocket` 切非阻塞、`closesocket` 关闭。
 *
 * 只连 `127.0.0.1`；调试通道不离开本机。
 */
private const val INVALID_FD = -1L
private const val LOOPBACK = "127.0.0.1"
private const val WINSOCK_VERSION: UShort = 0x0202u

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketInit(): Boolean = memScoped {
    val data = alloc<WSADATA>()
    WSAStartup(WINSOCK_VERSION, data.ptr) == 0
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketConnect(port: Int): Long {
    // INVALID_SOCKET 是全 1，转成有符号后正好是 -1；正常句柄都是正数
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd.toLong() < 0) return INVALID_FD

    val connected = memScoped {
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
        address.sin_family = AF_INET.convert()
        address.sin_port = htons(port.toShort()).toUShort()
        address.sin_addr.S_un.S_addr = inet_addr(LOOPBACK).convert()
        connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().toInt()) == 0
    }

    if (!connected) {
        closesocket(fd)
        return INVALID_FD
    }
    return fd.toLong()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketSetNonBlocking(fd: Long): Boolean = memScoped {
    val mode = alloc<UIntVar>()
    mode.value = 1u
    ioctlsocket(fd.toULong(), FIONBIO.toInt(), mode.ptr) == 0
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketRead(fd: Long, buffer: ByteArray, offset: Int, length: Int): Int {
    if (length <= 0) return -1
    val read = buffer.usePinned { recv(fd.toULong(), it.addressOf(offset), length, 0) }
    return if (read <= 0) -1 else read
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketWrite(fd: Long, buffer: ByteArray, offset: Int, length: Int): Boolean {
    var sent = 0
    while (sent < length) {
        val current = buffer.usePinned {
            send(fd.toULong(), it.addressOf(offset + sent), length - sent, 0)
        }
        if (current <= 0) return false
        sent += current
    }
    return true
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun debugSocketClose(fd: Long) {
    if (fd >= 0) closesocket(fd.toULong())
}

internal actual fun debugSleep(millis: Int) {
    usleep(millis.toUInt() * 1000u)
}
