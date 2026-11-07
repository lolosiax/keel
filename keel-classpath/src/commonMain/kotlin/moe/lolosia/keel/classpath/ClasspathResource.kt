package moe.lolosia.keel.classpath

/** 流式资源句柄。 */
interface ClasspathResource : AutoCloseable {

    val path: String

    /** 未压缩大小；未知时返回 -1。 */
    val size: Long

    /** 读入 [buffer]；返回读取字节数，结束返回 -1。 */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
}
