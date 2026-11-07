package moe.lolosia.keel.classpath

/** 读取并解码为文本（默认 UTF-8）。 */
fun Classpath.readText(path: String): String? = read(path)?.decodeToString()

/** 读取条目，不存在则抛异常（适合"必须有"的资源，如前端 index.html）。 */
fun Classpath.require(path: String): ByteArray =
    read(path) ?: throw NoSuchElementException("classpath 中不存在资源：$path")
