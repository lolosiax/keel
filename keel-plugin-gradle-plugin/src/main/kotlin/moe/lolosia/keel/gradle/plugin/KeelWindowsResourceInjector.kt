package moe.lolosia.keel.gradle.plugin

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 `windres -O res` 产出的资源写入已编译的 PE。
 *
 * 用 `BeginUpdateResourceW` / `UpdateResourceW` / `EndUpdateResourceW`：
 * 同名资源被替换（图标、VERSIONINFO），`IDR_KEEL_BUNDLE` 作为新资源追加。
 * 只在 Windows 宿主可用。
 */
internal object KeelWindowsResourceInjector {

    fun inject(exe: File, res: File) {
        val entries = parseRes(res)
        val kernel32 = Native.load("kernel32", Kernel32::class.java)
        val handle = kernel32.BeginUpdateResourceW(WString(exe.absolutePath), 0)
            ?: throw IllegalStateException(
                "BeginUpdateResourceW 失败：${exe.absolutePath}（GetLastError=${kernel32.GetLastError()}）"
            )

        val keepAlive = mutableListOf<Memory>()
        try {
            entries.forEach { entry ->
                val type = pointerOf(entry.type, keepAlive)
                val name = pointerOf(entry.name, keepAlive)
                val data = Memory(entry.data.size.toLong().coerceAtLeast(1))
                if (entry.data.isNotEmpty()) data.write(0, entry.data, 0, entry.data.size)
                keepAlive += data

                val ok = kernel32.UpdateResourceW(
                    handle,
                    type,
                    name,
                    entry.language.toShort(),
                    data,
                    entry.data.size,
                )
                if (ok == 0) {
                    throw IllegalStateException(
                        "UpdateResourceW 失败：type=${entry.type} name=${entry.name} " +
                            "（GetLastError=${kernel32.GetLastError()}）"
                    )
                }
            }
            if (kernel32.EndUpdateResourceW(handle, 0) == 0) {
                throw IllegalStateException(
                    "EndUpdateResourceW 失败（GetLastError=${kernel32.GetLastError()}）"
                )
            }
        } catch (e: Throwable) {
            runCatching { kernel32.EndUpdateResourceW(handle, 1) }
            throw e
        }
    }

    private fun pointerOf(id: ResId, keepAlive: MutableList<Memory>): Pointer = when (id) {
        is ResId.Ordinal -> Pointer(id.value.toLong())
        is ResId.Name -> {
            val bytes = id.value.toByteArray(Charsets.UTF_16LE)
            val memory = Memory(bytes.size.toLong() + 2)
            if (bytes.isNotEmpty()) memory.write(0, bytes, 0, bytes.size)
            memory.setShort(bytes.size.toLong(), 0)
            keepAlive += memory
            memory
        }
    }

    /**
     * 解析 `windres -O res` 的输出。
     *
     * 注意 binutils 的 `.res` 与 MS 文档里的结构有一处差异：类型 / 名称是
     * **无长度前缀的 UTF-16LE 字符串**（以 `0x0000` 结尾），序号则是 `0xFFFF` + WORD。
     * windres 还会在最前面写一条 `DataSize = 0` 的空条目，这里直接跳过。
     */
    private fun parseRes(file: File): List<ResEntry> {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableListOf<ResEntry>()
        while (buffer.remaining() >= 8) {
            val dataSize = buffer.int
            val headerSize = buffer.int
            if (headerSize < 8) break
            val type = readId(buffer)
            val name = readId(buffer)
            align(buffer)
            if (buffer.remaining() < 16) break

            buffer.int // DataVersion
            buffer.short // MemoryFlags
            val language = buffer.short.toInt() and 0xFFFF
            buffer.int // Version
            buffer.int // Characteristics

            if (dataSize < 0 || dataSize > buffer.remaining()) {
                throw IllegalArgumentException("${file.name} 的 .res 数据长度越界：$dataSize")
            }
            val data = ByteArray(dataSize)
            buffer.get(data)
            align(buffer)
            if (dataSize == 0) continue
            result += ResEntry(type, name, language, data)
        }
        return result
    }

    private fun readId(buffer: ByteBuffer): ResId {
        val first = buffer.short.toInt() and 0xFFFF
        if (first == 0xFFFF) {
            return ResId.Ordinal(buffer.short.toInt() and 0xFFFF)
        }
        // 无长度前缀：first 已经是第一个字符（UTF-16 码元）
        val chars = StringBuilder()
        if (first != 0) chars.append(first.toChar())
        while (first != 0 && buffer.remaining() >= 2) {
            val code = buffer.short.toInt() and 0xFFFF
            if (code == 0) break
            chars.append(code.toChar())
        }
        return ResId.Name(chars.toString())
    }

    private fun align(buffer: ByteBuffer) {
        while (buffer.position() % 4 != 0 && buffer.hasRemaining()) buffer.get()
    }

    private interface Kernel32 : Library {
        fun BeginUpdateResourceW(fileName: WString, deleteExistingResources: Int): Pointer?
        fun UpdateResourceW(
            handle: Pointer?,
            type: Pointer?,
            name: Pointer?,
            language: Short,
            data: Pointer?,
            dataSize: Int,
        ): Int
        fun EndUpdateResourceW(handle: Pointer?, discard: Int): Int
        fun GetLastError(): Int
    }

    private sealed class ResId {
        data class Ordinal(val value: Int) : ResId()
        data class Name(val value: String) : ResId()
    }

    private class ResEntry(
        val type: ResId,
        val name: ResId,
        val language: Int,
        val data: ByteArray,
    )
}
