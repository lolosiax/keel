package moe.lolosia.keel.logger

import java.io.File

/**
 * 源文件路径索引。
 *
 * IR 只暴露文件名，拿不到目录；这里按 Gradle 传入的源码根扫描一遍，
 * 用 `包名/文件名` 还原“相对于 Gradle 工作区”的路径。
 */
internal class SourcePathIndex(workspaceRoot: String?, private val sourceRoots: List<String>) {

    private val workspace: File? = workspaceRoot?.let { File(it).canonicalFile }

    private val index: Map<String, List<String>> by lazy { build() }

    fun relative(packageName: String, rawName: String): String {
        val name = rawName.replace('\\', '/')
        val base = workspace ?: return name

        // K2/原生下 fileEntry.name 常常已经是绝对路径，先相对化
        val candidate = File(name)
        if (candidate.isAbsolute) {
            val basePath = base.toPath()
            val filePath = candidate.canonicalFile.toPath()
            if (filePath.startsWith(basePath)) {
                return basePath.relativize(filePath).toString().replace(File.separatorChar, '/')
            }
            return name
        }
        if (name.contains('/')) return name

        return index["$packageName/$name"]?.firstOrNull() ?: name
    }

    private fun build(): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        for (rootPath in sourceRoots) {
            val root = File(rootPath)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val key = "${readPackage(file)}/${file.name}"
                    result.getOrPut(key) { ArrayList() }.add(pathOf(file, root))
                }
        }
        return result
    }

    private fun pathOf(file: File, root: File): String {
        val canonical = file.canonicalFile
        val base = workspace
        if (base != null) {
            val basePath = base.toPath()
            val filePath = canonical.toPath()
            if (filePath.startsWith(basePath)) {
                return basePath.relativize(filePath).toString().replace(File.separatorChar, '/')
            }
        }
        return root.canonicalFile.toPath()
            .relativize(canonical.toPath())
            .toString()
            .replace(File.separatorChar, '/')
    }

    private fun readPackage(file: File): String {
        val size = minOf(file.length(), 4096L).toInt()
        if (size <= 0) return ""
        val buffer = ByteArray(size)
        file.inputStream().use { it.read(buffer) }
        val text = buffer.decodeToString().removePrefix("\uFEFF")
        return PACKAGE_PATTERN.find(text)?.groupValues?.get(1) ?: ""
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("(?m)^\\s*package\\s+([\\w.]+)")
    }
}
