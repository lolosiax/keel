package moe.lolosia.keel.classpath

import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.*

/**
 * 磁盘目录实现（开发期回退）：直接读 [root] 下的文件。
 *
 * `entries` / `list` 不做目录遍历，恒为空，只支持按路径 [read]。
 */
class DiskClasspath(private val root: String) : Classpath {

    override val entries: List<String> = emptyList()

    override fun read(path: String): ByteArray? {
        val rel = normalizeClasspathPath(path)
        if (rel.isEmpty() || hasParentSegment(rel)) return null
        return readFileBytes("${root.trimEnd('/')}/$rel")
    }

    override fun list(prefix: String): List<String> = emptyList()

    override val scheme: String = "file"

    override val origin: String get() = "$scheme://$root"
}

/**
 * 读取整个文件（POSIX）。
 *
 * mingw 上 C 的 `long` 是 32 位，`ftell`/`fseek` 的签名与 Linux 不同，这里统一转成 `Long`。
 */
@OptIn(UnsafeNumber::class)
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
internal fun readFileBytes(absPath: String): ByteArray? {
    val file = fopen(absPath, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val length = ftell(file).toLong()
        if (length <= 0L) return null
        rewind(file)
        val out = ByteArray(length.toInt())
        val readCount = out.usePinned { pinned ->
            fread(pinned.addressOf(0), 1uL, length.toULong(), file).toLong()
        }
        return if (readCount == length) out else null
    } finally {
        fclose(file)
    }
}
