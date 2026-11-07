package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

/** mingwX64 与 linux 的 `platform.posix` 都提供 opendir / readdir，这里只保留一份实现。 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun listNames(dir: String): List<String>? {
    val handle = opendir(dir) ?: return null
    val result = mutableListOf<String>()
    try {
        while (true) {
            val entry = readdir(handle) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name == "." || name == "..") continue
            result += name
        }
    } finally {
        closedir(handle)
    }
    return result
}
