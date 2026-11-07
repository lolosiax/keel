package moe.lolosia.keel.classpath

/** 既没嵌入资源也没配回退目录时使用的空实现。 */
object EmptyClasspath : Classpath {

    override val entries: List<String> = emptyList()

    override fun read(path: String): ByteArray? = null

    override fun open(path: String): ClasspathResource? = null

    override fun list(prefix: String): List<String> = emptyList()

    override fun exists(path: String): Boolean = false

    override val scheme: String = "empty"
}
