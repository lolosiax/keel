package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.mkdir

/**
 * mingw 的 `mkdir` 只有 path 一个参数（对应 `_mkdir`），没有 mode。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun mkdirs(path: String) {
    var current = ""
    path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.forEach { part ->
        current = if (current.isEmpty()) part else "$current/$part"
        // 盘符（`C:`）本身不是可创建目录
        if (current.endsWith(":")) return@forEach
        mkdir(current)
    }
}

internal actual fun nativePath(path: String): String = path.replace('/', '\\')

@OptIn(ExperimentalForeignApi::class)
internal actual fun userCacheRoot(): String? {
    val base = getenv("LOCALAPPDATA")?.toKString()
        ?: getenv("USERPROFILE")?.toKString()?.let { "$it/AppData/Local" }
    return base?.let { "$it/keel/cache" }
}
