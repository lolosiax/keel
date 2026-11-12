package moe.lolosia.keel.classpath

/**
 * Linux：从可执行文件尾部的 trailer 取 `keel-bundle.zip`。
 *
 * trailer 固定 32 字节，按小端存放：
 * ```
 * offset: u64   // bundle 在文件内的偏移
 * size:   u64
 * format: u32   // 1
 * crc32:  u32
 * magic:  8 bytes "KEELBNDL"
 * ```
 */
actual fun hasEmbeddedPluginBundle(executablePath: String): Boolean {
    val file = try {
        RandomAccessFile(executablePath)
    } catch (_: Throwable) {
        return false
    }
    try {
        if (file.size < FOOTER_SIZE) return false
        val footer = file.readAt(file.size - FOOTER_SIZE, FOOTER_SIZE)
        return footer.decodeToString(24, 32) == FOOTER_MAGIC
    } finally {
        file.close()
    }
}

internal actual fun readEmbeddedPluginBundle(executablePath: String): ByteArray? {
    val file = try {
        RandomAccessFile(executablePath)
    } catch (_: Throwable) {
        return null
    }
    try {
        if (file.size < FOOTER_SIZE) return null
        val footer = file.readAt(file.size - FOOTER_SIZE, FOOTER_SIZE)
        if (footer.decodeToString(24, 32) != FOOTER_MAGIC) return null

        val offset = u64(footer, 0)
        val size = u64(footer, 8)
        val format = u32(footer, 16)
        val crc = u32(footer, 20)
        if (format != FORMAT_VERSION) return null
        if (offset < 0 || size <= 0 || size > Int.MAX_VALUE) return null
        if (offset > file.size || size > file.size - offset) return null

        val bundle = file.readAt(offset, size.toInt())
        if (crc32(bundle) != crc) return null
        return bundle
    } finally {
        file.close()
    }
}

private fun u32(bytes: ByteArray, offset: Int): Long {
    var result = 0L
    for (i in 0 until 4) {
        result = result or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
    }
    return result
}

private fun u64(bytes: ByteArray, offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = result or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
    }
    return result
}

private fun crc32(bytes: ByteArray): Long {
    var crc = 0xFFFFFFFFL
    bytes.forEach { byte ->
        crc = (crc ushr 8) xor CRC_TABLE[((crc xor (byte.toLong() and 0xFF)) and 0xFF).toInt()]
    }
    return crc xor 0xFFFFFFFFL
}

private val CRC_TABLE = LongArray(256) { index ->
    var value = index.toLong()
    repeat(8) {
        value = if ((value and 1L) != 0L) (value ushr 1) xor 0xEDB88320L else value ushr 1
    }
    value
}

private const val FOOTER_SIZE = 32
private const val FORMAT_VERSION = 1L
private const val FOOTER_MAGIC = "KEELBNDL"
