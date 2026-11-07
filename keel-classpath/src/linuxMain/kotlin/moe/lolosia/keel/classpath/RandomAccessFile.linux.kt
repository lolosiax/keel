package moe.lolosia.keel.classpath

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

internal actual class RandomAccessFile actual constructor(path: String) : AutoCloseable {

    private val file = fopen(path, "rb")
        ?: throw IllegalArgumentException("无法打开文件：$path")

    actual val size: Long = run {
        check(fseek(file, 0, SEEK_END) == 0) { "fseek 失败：$path" }
        ftell(file).toLong()
    }

    actual fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        check(fseek(file, offset, SEEK_SET) == 0) { "fseek 失败：offset=$offset" }
        val out = ByteArray(length)
        val read = out.usePinned { pinned ->
            fread(pinned.addressOf(0), 1uL, length.toULong(), file).toInt()
        }
        check(read == length) { "读取不足：期望 $length，实际 $read" }
        return out
    }

    actual override fun close() {
        fclose(file)
    }
}
