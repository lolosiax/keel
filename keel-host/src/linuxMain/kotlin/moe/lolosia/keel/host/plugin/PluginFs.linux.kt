package moe.lolosia.keel.host.plugin

import platform.posix.*

internal actual fun fileSizeOrNull(path: String): Long? {
    val file = fopen(path, "rb") ?: return null
    return try {
        if (fseek(file, 0L, SEEK_END) != 0) return null
        ftell(file)
    } finally {
        fclose(file)
    }
}
