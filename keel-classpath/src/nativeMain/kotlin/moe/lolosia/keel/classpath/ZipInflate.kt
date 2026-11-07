package moe.lolosia.keel.classpath

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

/** raw deflate（zip 条目的存储格式），`windowBits = -15`。 */
internal fun inflateRaw(input: ByteArray, outputSize: Int): ByteArray {
    if (outputSize == 0) return ByteArray(0)
    return memScoped {
        val strm = alloc<z_stream>()
        val version = zlibVersion()?.toKString() ?: "1.2.0"
        val init = inflateInit2_(strm.ptr, -15, version, sizeOf<z_stream>().convert())
        check(init == Z_OK) { "inflateInit2 失败：rc=$init" }
        try {
            val out = ByteArray(outputSize)
            input.usePinned { inPinned ->
                out.usePinned { outPinned ->
                    strm.next_in = inPinned.addressOf(0).reinterpret()
                    strm.avail_in = input.size.convert()
                    strm.next_out = outPinned.addressOf(0).reinterpret()
                    strm.avail_out = outputSize.convert()
                    val rc = inflate(strm.ptr, Z_FINISH)
                    check(rc == Z_STREAM_END) { "inflate 失败：rc=$rc" }
                }
            }
            out
        } finally {
            inflateEnd(strm.ptr)
        }
    }
}
