package moe.lolosia.keel.classpath

/**
 * 文件型 zip 读取：只读 EOCD、中央目录与目标条目，不把整个 `.keel` 载入内存。
 */
internal class ZipFileClasspath(private val filePath: String) : Classpath {

    private class Entry(
        val method: Int,
        val localHeaderOffset: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
    )

    private val byName: Map<String, Entry> = readIndex()

    override val entries: List<String> get() = byName.keys.sorted()

    override fun read(path: String): ByteArray? {
        val entry = byName[normalizeClasspathPath(path)] ?: return null
        RandomAccessFile(filePath).use { file ->
            val header = file.readAt(entry.localHeaderOffset.toLong(), LOCAL_HEADER_SIZE)
            check(u32(header, 0) == SIG_LOCAL_FILE_HEADER) { "zip 本地头损坏：$path" }
            val nameLen = u16(header, 26)
            val extraLen = u16(header, 28)
            val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLen + extraLen
            val compressed = file.readAt(dataOffset.toLong(), entry.compressedSize)
            return when (entry.method) {
                METHOD_STORED -> compressed
                METHOD_DEFLATED -> inflateRaw(compressed, entry.uncompressedSize)
                else -> throw UnsupportedOperationException("不支持的 zip 压缩方式 ${entry.method}：$path")
            }
        }
    }

    override fun list(prefix: String): List<String> {
        val p = normalizeClasspathPath(prefix)
        return byName.keys.filter { p.isEmpty() || it.startsWith(p) }.sorted()
    }

    override val scheme: String = "keel"

    override val origin: String get() = "keel://$filePath"

    private fun readIndex(): Map<String, Entry> {
        RandomAccessFile(filePath).use { file ->
            val tailLength = minOf(file.size, 22L + 0xFFFF).toInt()
            if (tailLength < 22) return emptyMap()

            val tail = file.readAt(file.size - tailLength, tailLength)
            val eocd = findEndOfCentralDirectory(tail) ?: return emptyMap()

            val count = u16(tail, eocd + 10)
            val centralSize = u32(tail, eocd + 12)
            val centralOffset = u32(tail, eocd + 16)
            if (centralSize <= 0 || centralOffset < 0) return emptyMap()

            val central = file.readAt(centralOffset.toLong(), centralSize)
            val result = LinkedHashMap<String, Entry>(count)
            var offset = 0

            repeat(count) {
                if (offset + CENTRAL_HEADER_SIZE > central.size) return@repeat
                if (u32(central, offset) != SIG_CENTRAL_FILE_HEADER) return@repeat

                val method = u16(central, offset + 10)
                val compressedSize = u32(central, offset + 20)
                val uncompressedSize = u32(central, offset + 24)
                val nameLen = u16(central, offset + 28)
                val extraLen = u16(central, offset + 30)
                val commentLen = u16(central, offset + 32)
                val localHeaderOffset = u32(central, offset + 42)

                if (offset + CENTRAL_HEADER_SIZE + nameLen > central.size) return@repeat
                val name = central.decodeToString(
                    offset + CENTRAL_HEADER_SIZE,
                    offset + CENTRAL_HEADER_SIZE + nameLen,
                )
                if (!name.endsWith("/")) {
                    result[name] = Entry(method, localHeaderOffset, compressedSize, uncompressedSize)
                }

                offset += CENTRAL_HEADER_SIZE + nameLen + extraLen + commentLen
            }
            return result
        }
    }

    private fun findEndOfCentralDirectory(tail: ByteArray): Int? {
        var i = tail.size - 22
        while (i >= 0) {
            if (u32(tail, i) == SIG_END_OF_CENTRAL_DIRECTORY) return i
            i--
        }
        return null
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun u32(data: ByteArray, offset: Int): Int =
        u8(data, offset) or
            (u8(data, offset + 1) shl 8) or
            (u8(data, offset + 2) shl 16) or
            (u8(data, offset + 3) shl 24)

    private companion object {
        const val SIG_LOCAL_FILE_HEADER = 0x04034b50
        const val SIG_CENTRAL_FILE_HEADER = 0x02014b50
        const val SIG_END_OF_CENTRAL_DIRECTORY = 0x06054b50

        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        const val LOCAL_HEADER_SIZE = 30
        const val CENTRAL_HEADER_SIZE = 46
    }
}
