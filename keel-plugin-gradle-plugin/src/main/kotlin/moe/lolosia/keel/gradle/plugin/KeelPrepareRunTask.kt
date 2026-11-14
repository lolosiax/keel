package moe.lolosia.keel.gradle.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * 组装 `build/keel-run/` 的插件目录，不启动 Host。
 *
 * 这一步只做「打包 + 拷贝」，供 IDE 的运行配置在启动 Host 前调用；
 * 命令行仍可用 `keelRun` 一次完成构建与启动。
 */
@DisableCachingByDefault(because = "只是把包与配置拷进本地运行目录")
abstract class KeelPrepareRunTask : DefaultTask() {

    /** 本插件的 `.keel`（`keelPackage` 产物）。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginPackages: ConfigurableFileCollection

    /** 额外工件：能力包、被依赖的插件。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val extraArtifacts: ConfigurableFileCollection

    /** 位置表（`*.keel-debug`）：本插件与显式配置的本地 Host。 */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val debugFiles: ConfigurableFileCollection

    /** `dev/config/plugins/<id>.yaml`。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configTemplates: ConfigurableFileCollection

    /** `dev/data/`：启动前复制到运行目录的样例数据。 */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataTemplate: DirectoryProperty

    /** 运行目录；Host 解包目录与插件目录都在它下面。 */
    @get:Internal
    abstract val runDirectory: DirectoryProperty

    /** 插件目录；`.keel` 与配置模板都放这里。 */
    @get:OutputDirectory
    abstract val pluginsDirectory: DirectoryProperty

    /** 调试位置表目录；IDE 从这里读「文件 + 行 → locationId」。 */
    @get:OutputDirectory
    abstract val debugDirectory: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun prepare() {
        val root = runDirectory.get().asFile
        val plugins = pluginsDirectory.get().asFile
        fs.delete { it.delete(plugins) }
        plugins.mkdirs()

        pluginPackages.files.forEach { it.copyTo(plugins.resolve(it.name), overwrite = true) }
        extraArtifacts.files.forEach { it.copyTo(plugins.resolve(it.name), overwrite = true) }

        if (!configTemplates.isEmpty) {
            fs.copy {
                it.from(configTemplates)
                it.into(plugins.resolve(".cache/config/plugins"))
            }
        }
        dataTemplate.orNull?.let { source ->
            fs.copy {
                it.from(source)
                it.into(root)
            }
        }

        // 位置表与 `.keel` 分开存放：它只在调试会话里有意义，也不参与包校验
        val debug = debugDirectory.get().asFile
        fs.delete { it.delete(debug) }
        debug.mkdirs()
        debugFiles.files.forEach { it.copyTo(debug.resolve(it.name), overwrite = true) }
    }
}
