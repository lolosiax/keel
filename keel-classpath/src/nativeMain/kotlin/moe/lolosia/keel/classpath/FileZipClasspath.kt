package moe.lolosia.keel.classpath

/**
 * 读取磁盘上的 zip 文件（`.keel` 包）。
 *
 * 通过 [ZipFileClasspath] 只读中央目录与目标条目，不把整个包载入内存。
 *
 * @param filePath `.keel` 或普通 zip 的绝对路径
 * @param prefix 资源在 zip 内的前缀，例如 `.keel` 的 `resources/`
 */
class FileZipClasspath private constructor(
    private val delegate: Classpath,
    private val filePath: String,
) : Classpath {

    override val entries: List<String> get() = delegate.entries

    override fun read(path: String): ByteArray? = delegate.read(path)

    override fun open(path: String): ClasspathResource? = delegate.open(path)

    override fun list(prefix: String): List<String> = delegate.list(prefix)

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun lastModified(path: String): Long? = delegate.lastModified(path)

    override val scheme: String = "keel"

    override val origin: String get() = "keel://$filePath"

    companion object {

        /**
         * 打开 zip 文件；[prefix] 为空表示 zip 根本身就是资源根。
         *
         * @throws IllegalArgumentException 文件不存在或不是有效 zip
         */
        fun open(filePath: String, prefix: String = ""): FileZipClasspath {
            val zip = ZipFileClasspath(filePath)
            val delegate: Classpath =
                if (prefix.isBlank()) zip else PrefixedClasspath(zip, prefix)
            return FileZipClasspath(delegate, filePath)
        }
    }
}
