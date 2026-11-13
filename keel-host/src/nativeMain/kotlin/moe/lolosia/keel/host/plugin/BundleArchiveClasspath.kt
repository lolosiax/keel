package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.lolosia.keel.abi.c.ZSTD_decompress
import moe.lolosia.keel.abi.c.ZSTD_isError
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.classpath.normalizeClasspathPath

/**
 * 内嵌 bundle 的 `KELB` 容器（单帧 zstd + 文件索引）。
 *
 * 布局（little-endian）：
 * ```
 * 0   magic "KELB"
 * 4   u8  version = 2
 * 5   u8  format  = 1 (zstd)
 * 6   u16 flags
 * 8   u32 indexLength
 * 12  u32 rawSize      解压后总大小
 * 16  u32 frameSize    zstd 帧长度
 * 20  index            UTF-8 JSON：{"entries":[{"name":..,"size":..}]}
 * 20+indexLength       zstd 帧（各条目按索引顺序首尾相接）
 * ```
 *
 * 打开时一次性解压到内存：与原来的内存 zip 一样，bundle 不落盘；
 * 单帧的好处是跨文件（尤其两个插件 DLL）共享压缩窗口。
 * 常量必须与 Gradle 侧 `KeelBundleTask` 保持一致。
 */
internal class BundleArchiveClasspath private constructor(
    private val data: ByteArray,
    private val index: List<Entry>,
) : Classpath {

    private class Entry(val name: String, val offset: Int, val size: Int)

    private val byName: Map<String, Entry> = index.associateBy { it.name }

    override val entries: List<String> get() = index.map { it.name }

    override fun read(path: String): ByteArray? {
        if (hasParentSegment(path)) return null
        val entry = byName[normalizeClasspathPath(path)] ?: return null
        return data.copyOfRange(entry.offset, entry.offset + entry.size)
    }

    override fun list(prefix: String): List<String> {
        if (hasParentSegment(prefix)) return emptyList()
        val p = normalizeClasspathPath(prefix)
        return entries.filter { p.isEmpty() || it.startsWith(p) }
    }

    override val scheme: String = "bundle"

    override val origin: String = "bundle://"

    companion object {

        private const val HEADER_SIZE = 20
        private const val MAGIC = "KELB"
        private const val VERSION = 2
        private const val FORMAT_ZSTD = 1

        /** 解压上限，防止损坏的包头把内存打爆（当前 bundle 约 20MB）。 */
        private const val MAX_RAW_SIZE = 512 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private class Index(val entries: List<Item>)

        @Serializable
        private class Item(val name: String, val size: Int)

        /** 是否是 `KELB` 容器；不是则交回 zip 路径。 */
        fun matches(bytes: ByteArray): Boolean =
            bytes.size >= HEADER_SIZE && bytes.decodeToString(0, 4) == MAGIC

        /** 解析并解压；格式损坏时返回 null（调用方回退到 zip / 空 bundle）。 */
        fun open(bytes: ByteArray): Classpath? {
            if (bytes.size < HEADER_SIZE || bytes.decodeToString(0, 4) != MAGIC) return null
            val version = bytes[4].toInt() and 0xFF
            val format = bytes[5].toInt() and 0xFF
            if (version != VERSION) {
                Log.warn("内嵌 bundle 版本不支持：$version", tag = "Loader")
                return null
            }
            if (format != FORMAT_ZSTD) {
                Log.warn("内嵌 bundle 压缩格式不支持：$format", tag = "Loader")
                return null
            }

            val indexLength = u32(bytes, 8)
            val rawSize = u32(bytes, 12)
            val frameSize = u32(bytes, 16)
            if (indexLength <= 0 || rawSize <= 0 || frameSize <= 0 || rawSize > MAX_RAW_SIZE) return null
            if (HEADER_SIZE.toLong() + indexLength + frameSize > bytes.size) return null

            val indexBytes = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + indexLength)
            val frameOffset = HEADER_SIZE + indexLength
            val parsed = runCatching {
                json.decodeFromString<Index>(indexBytes.decodeToString())
            }.getOrElse { error ->
                Log.warn("内嵌 bundle 索引解析失败：${error.message}", tag = "Loader")
                return null
            }

            val raw = inflate(bytes, frameOffset, frameSize, rawSize) ?: return null
            if (raw.size != rawSize) {
                Log.warn("内嵌 bundle 解压大小不符：${raw.size} != $rawSize", tag = "Loader")
                return null
            }

            var offset = 0
            val entries = ArrayList<Entry>(parsed.entries.size)
            for (item in parsed.entries) {
                if (item.size < 0 || offset.toLong() + item.size > raw.size) return null
                entries += Entry(normalizeClasspathPath(item.name), offset, item.size)
                offset += item.size
            }
            if (offset != raw.size) return null
            return BundleArchiveClasspath(raw, entries)
        }

        private fun inflate(bytes: ByteArray, offset: Int, size: Int, rawSize: Int): ByteArray? {
            if (size <= 0) return null
            val source = bytes.copyOfRange(offset, offset + size)
            val target = ByteArray(rawSize)
            val result = source.usePinned { src ->
                target.usePinned { dst ->
                    ZSTD_decompress(
                        dst.addressOf(0),
                        rawSize.convert(),
                        src.addressOf(0),
                        size.convert(),
                    )
                }
            }
            if (ZSTD_isError(result) != 0u) {
                Log.warn("内嵌 bundle 解压失败：code=$result", tag = "Loader")
                return null
            }
            if (result.toLong() != rawSize.toLong()) return null
            return target
        }

        private fun u32(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

        private fun hasParentSegment(path: String): Boolean =
            normalizeClasspathPath(path).split('/').any { it == ".." }
    }
}
