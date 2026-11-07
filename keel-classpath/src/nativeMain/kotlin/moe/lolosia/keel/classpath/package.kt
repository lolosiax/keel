package moe.lolosia.keel.classpath

/** 打开磁盘上的 zip 作为 Classpath；[prefix] 用于 `.keel` 的 `resources/`。 */
fun classpathFromZipFile(filePath: String, prefix: String = ""): Classpath =
    FileZipClasspath.open(filePath, prefix)

/** 打开目录作为 Classpath（开发期回退）。 */
fun classpathFromDirectory(root: String): Classpath = DiskClasspath(root)
