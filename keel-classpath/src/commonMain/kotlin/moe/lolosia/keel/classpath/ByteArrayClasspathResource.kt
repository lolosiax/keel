package moe.lolosia.keel.classpath

/** 把 [read] 的结果包装成流式句柄；文件型实现应覆盖成真正的分段读取。 */
class ByteArrayClasspathResource(
    override val path: String,
    private val bytes: ByteArray,
) : ClasspathResource {

    private var position: Int = 0

    override val size: Long get() = bytes.size.toLong()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override fun close() = Unit
}
