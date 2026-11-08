package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.remove

/**
 * Host 侧最小文件读写，只覆盖插件加载 / 安装需要的场景。
 *
 * 故意不引入通用 IO 抽象：这里只有「查大小 / 覆盖写 / 整读 / 删除」四个动作，
 * 配置落盘、日志等其它需求应各自处理。
 */
internal object PluginFs {

    /** 覆盖写入整个文件；父目录必须已存在。空数组会创建一个空文件。 */
    @OptIn(ExperimentalForeignApi::class)
    fun writeBytes(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: throw IllegalArgumentException("无法写入文件：$path")
        try {
            if (bytes.isEmpty()) return
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
            }
            check(written.toInt() == bytes.size) { "写入不完整：$path" }
        } finally {
            fclose(file)
        }
    }

    /** 整读一个文件；不存在或不可读时抛 [IllegalArgumentException]。 */
    @OptIn(ExperimentalForeignApi::class)
    fun readBytes(path: String): ByteArray {
        val file = fopen(path, "rb") ?: throw IllegalArgumentException("无法读取文件：$path")
        try {
            val chunks = mutableListOf<ByteArray>()
            var total = 0
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = buffer.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1uL, buffer.size.toULong(), file).toInt()
                }
                if (read <= 0) break
                chunks += buffer.copyOf(read)
                total += read
            }
            val result = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            return result
        } finally {
            fclose(file)
        }
    }

    /** 删除文件；返回 false 表示失败（不存在、被占用或无权限）。 */
    fun delete(path: String): Boolean = remove(path) == 0
}

/**
 * 文件字节数；文件不存在或不可读返回 null。
 *
 * `fseek` 的偏移参数在 mingw 上是 32 位、linux 上是 64 位，签名不一致，
 * 所以按目标分开实现，不能放在 nativeMain。
 */
internal expect fun fileSizeOrNull(path: String): Long?
