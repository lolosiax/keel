package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.mkdir

/** linux 的 `mkdir` 是 POSIX 形式：path + mode。 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun mkdirs(path: String) {
    var current = ""
    path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.forEach { part ->
        current = if (current.isEmpty()) part else "$current/$part"
        mkdir(current, 0x1FFu)
    }
}

internal actual fun nativePath(path: String): String = path

@OptIn(ExperimentalForeignApi::class)
internal actual fun userCacheRoot(): String? {
    val base = getenv("XDG_CACHE_HOME")?.toKString()
        ?: getenv("HOME")?.toKString()?.let { "$it/.cache" }
    return base?.let { "$it/keel" }
}
