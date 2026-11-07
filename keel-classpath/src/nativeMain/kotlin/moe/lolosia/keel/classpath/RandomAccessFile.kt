package moe.lolosia.keel.classpath

/**
 * 只读随机访问文件；用于 `.keel` 的 zip 中央目录与条目读取。
 *
 * 实现按目标平台分开，避免 `fseek` 的 `long` 宽度差异。
 */
internal expect class RandomAccessFile(path: String) : AutoCloseable {

    val size: Long

    /** 从 [offset] 读取 [length] 字节；文件不足时抛异常。 */
    fun readAt(offset: Long, length: Int): ByteArray

    /** 元数据编译看不到各目标的 actual，继承来的抽象成员必须在这里显式声明。 */
    override fun close()
}
