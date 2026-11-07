package moe.lolosia.keel.classpath

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret

/**
 * 读取内存映像中的 zip。
 *
 * 只解析中央目录建索引，读取条目时才拷贝该条目，因此内存占用 ≈ 映像本体 + 单条目。
 * 打开时会把映像数据拷入一次内存，使解析逻辑与平台无关；文件型读取见 [FileZipClasspath]。
 *
 * 支持 STORED 与 DEFLATED（用 `platform.zlib` 做 raw inflate）。
 * 已知边界：不支持 zip64（classpath 不会超过 4GB）。
 */
class ZipClasspath private constructor(
    private val data: ByteArray,
    private val lastModifiedAt: Long?,
) : Classpath {

    private class Entry(
        val method: Int,
        val dataOffset: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
    )

    private val byName: Map<String, Entry> = parseCentralDirectory()

    override val entries: List<String> get() = byName.keys.sorted()

    override fun read(path: String): ByteArray? {
        val entry = byName[normalizeClasspathPath(path)] ?: return null
        val compressed = data.copyOfRange(entry.dataOffset, entry.dataOffset + entry.compressedSize)
        return when (entry.method) {
            METHOD_STORED -> compressed
            METHOD_DEFLATED -> inflateRaw(compressed, entry.uncompressedSize)
            else -> throw UnsupportedOperationException("不支持的 zip 压缩方式 ${entry.method}：$path")
        }
    }

    override fun list(prefix: String): List<String> {
        val p = normalizeClasspathPath(prefix)
        return byName.keys.filter { p.isEmpty() || it.startsWith(p) }.sorted()
    }

    override fun lastModified(path: String): Long? =
        if (byName.containsKey(normalizeClasspathPath(path))) lastModifiedAt else null

    override val scheme: String = "classpath"

    private fun parseCentralDirectory(): Map<String, Entry> {
        val eocd = findEndOfCentralDirectory() ?: return emptyMap()
        val count = u16(eocd + 10)
        var offset = u32(eocd + 16)
        val result = LinkedHashMap<String, Entry>(count)

        var i = 0
        while (i < count && offset + 46 <= data.size) {
            if (u32(offset) != SIG_CENTRAL_FILE_HEADER) break

            val method = u16(offset + 10)
            val compressedSize = u32(offset + 20)
            val uncompressedSize = u32(offset + 24)
            val nameLen = u16(offset + 28)
            val extraLen = u16(offset + 30)
            val commentLen = u16(offset + 32)
            val localHeaderOffset = u32(offset + 42)
            val name = data.decodeToString(offset + 46, offset + 46 + nameLen)

            val dataOffset = localDataOffset(localHeaderOffset)
            if (!name.endsWith("/")) {
                result[name] = Entry(method, dataOffset, compressedSize, uncompressedSize)
            }

            offset += 46 + nameLen + extraLen + commentLen
            i++
        }
        return result
    }

    private fun localDataOffset(localHeaderOffset: Int): Int {
        if (localHeaderOffset + 30 > data.size) return localHeaderOffset
        if (u32(localHeaderOffset) != SIG_LOCAL_FILE_HEADER) return localHeaderOffset + 30
        val nameLen = u16(localHeaderOffset + 26)
        val extraLen = u16(localHeaderOffset + 28)
        return localHeaderOffset + 30 + nameLen + extraLen
    }

    private fun findEndOfCentralDirectory(): Int? {
        if (data.size < 22) return null
        val minPos = maxOf(0, data.size - 22 - 0xFFFF)
        var i = data.size - 22
        while (i >= minPos) {
            if (u32(i) == SIG_END_OF_CENTRAL_DIRECTORY) return i
            i--
        }
        return null
    }

    private fun u8(o: Int): Int = data[o].toInt() and 0xFF

    private fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)

    private fun u32(o: Int): Int =
        u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)

    companion object {
        private const val SIG_LOCAL_FILE_HEADER = 0x04034b50
        private const val SIG_CENTRAL_FILE_HEADER = 0x02014b50
        private const val SIG_END_OF_CENTRAL_DIRECTORY = 0x06054b50

        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        /** 从可执行文件映像内的 zip 打开（会拷贝一次映像数据）。 */
        fun open(image: COpaquePointer, size: Int, lastModifiedAt: Long? = null): ZipClasspath =
            ZipClasspath(image.reinterpret<ByteVar>().readBytes(size), lastModifiedAt)

        /** 从内存字节打开。 */
        fun open(bytes: ByteArray, lastModifiedAt: Long? = null): ZipClasspath =
            ZipClasspath(bytes, lastModifiedAt)
    }
}
