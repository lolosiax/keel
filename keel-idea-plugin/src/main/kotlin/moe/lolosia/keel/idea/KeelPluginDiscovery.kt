package moe.lolosia.keel.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 静态扫描工作区里的 Keel 插件工程。
 *
 * 不依赖 IDEA 的 Gradle 同步内部 API：插件工程的判据是
 * `src/commonMain/resources/plugin.json` 与构建脚本里的
 * `moe.lolosia.keel.plugin`。这样独立插件仓库与 keel 本仓库都能识别。
 */
object KeelPluginDiscovery {

    private const val MANIFEST = "src/commonMain/resources/plugin.json"
    private const val BUILD_SCRIPT = "build.gradle.kts"
    private const val PLUGIN_ID = "moe.lolosia.keel.plugin"
    private const val MAX_DEPTH = 8

    private val ignored = setOf("build", ".gradle", ".git", ".idea", "node_modules", "out")

    fun scan(project: Project): List<KeelPluginProject> =
        ProjectRootManager.getInstance(project).contentRoots
            .flatMap { scanRoot(VfsUtilCore.virtualToIoFile(it).toPath()) }
            .distinctBy { it.directory }
            .sortedBy { it.displayName }

    fun findPluginProject(file: VirtualFile): KeelPluginProject? =
        findPluginProject(VfsUtilCore.virtualToIoFile(file).toPath())

    fun findPluginProject(path: Path): KeelPluginProject? {
        var current: Path? = if (Files.isDirectory(path)) path else path.parent
        while (current != null) {
            val manifest = current.resolve(MANIFEST)
            val buildScript = current.resolve(BUILD_SCRIPT)
            if (Files.isRegularFile(manifest) &&
                Files.isRegularFile(buildScript) &&
                Files.readString(buildScript).contains(PLUGIN_ID)
            ) {
                return projectAt(current, Files.readString(manifest))
            }
            current = current.parent
        }
        return null
    }

    private fun scanRoot(root: Path): List<KeelPluginProject> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = mutableListOf<KeelPluginProject>()
        Files.walkFileTree(root, emptySet<FileVisitOption>(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString()
                if (dir != root && name != null && name in ignored) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                val manifest = dir.resolve(MANIFEST)
                if (Files.isRegularFile(manifest)) {
                    val buildScript = dir.resolve(BUILD_SCRIPT)
                    if (Files.isRegularFile(buildScript) &&
                        Files.readString(buildScript).contains(PLUGIN_ID)
                    ) {
                        found += projectAt(dir, Files.readString(manifest))
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
        return found
    }

    private fun projectAt(directory: Path, manifest: String): KeelPluginProject {
        val id = field(manifest, "id") ?: directory.fileName.toString()
        val name = field(manifest, "name") ?: id
        return KeelPluginProject(directory, id, name)
    }

    /** 只读 `plugin.json` 的两个字符串字段；避免为扫描引入 JSON 依赖。 */
    private fun field(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
}
