package moe.lolosia.keel.host.plugin

import platform.posix.*

internal actual fun fileSizeOrNull(path: String): Long? {
    val file = fopen(path, "rb") ?: return null
    return try {
        // mingw 的 C long 是 32 位
        if (fseek(file, 0, SEEK_END) != 0) return null
        ftell(file).toLong()
    } finally {
        fclose(file)
    }
}
