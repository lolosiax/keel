package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * Host 侧最小文件读写，只覆盖插件加载需要的场景。
 *
 * 故意不引入通用 IO 抽象：这里只有「查大小 / 覆盖写」两个动作，
 * 配置落盘、日志等其它需求应各自处理。
 */
internal object PluginFs {

    /** 覆盖写入整个文件；父目录必须已存在。 */
    @OptIn(ExperimentalForeignApi::class)
    fun writeBytes(path: String, bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "拒绝写入空文件：$path" }
        val file = fopen(path, "wb") ?: throw IllegalArgumentException("无法写入文件：$path")
        try {
            val written = bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
            }
            check(written.toInt() == bytes.size) { "写入不完整：$path" }
        } finally {
            fclose(file)
        }
    }
}

/**
 * 文件字节数；文件不存在或不可读返回 null。
 *
 * `fseek` 的偏移参数在 mingw 上是 32 位、linux 上是 64 位，签名不一致，
 * 所以按目标分开实现，不能放在 nativeMain。
 */
internal expect fun fileSizeOrNull(path: String): Long?
