package moe.lolosia.keel.gradle.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * `keelRun`：把插件包、能力包与配置组装进一个开发工作目录，然后启动 Host。
 *
 * 等价于 Bukkit 的 `runServer` / Forge 的 `runServer`：插件没有独立进程，必须由宿主加载。
 * 这是一个**始终执行**的任务（不参与 up-to-date），因为它的目的就是跑起来。
 *
 * 目录布局：
 * ```
 * build/keel-run/
 *   host/keel-host[.exe]            # Maven 解析出的 Host 包解压结果
 *   plugins/<id>.keel               # 本插件 + 能力包
 *   plugins/.cache/config/plugins/  # HostConfigs 的 workDir 是 cacheDir
 *   davroot/ ...                    # dev/data 复制过来的运行期文件
 * ```
 */
abstract class KeelRunTask : DefaultTask() {

    /** 本插件的 `.keel`（`keelPackage` 产物）。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluginPackages: ConfigurableFileCollection

    /** 额外工件：能力包、被依赖的插件。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val extraArtifacts: ConfigurableFileCollection

    /** 从 Maven 解析到的 Host 包（`keel-host-<target>@zip`）。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hostArchive: ConfigurableFileCollection

    /** 本地 Host 可执行文件；优先于 [hostArchive]。 */
    @get:InputFile
    @get:Optional
    abstract val hostExecutable: RegularFileProperty

    /** `dev/config/plugins/<id>.yaml`。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configTemplates: ConfigurableFileCollection

    /** `dev/data/`：启动前复制到运行目录的样例数据。 */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataTemplate: DirectoryProperty

    @get:OutputDirectory
    abstract val runDirectory: DirectoryProperty

    @get:Input
    abstract val port: Property<Int>

    @get:Input
    @get:Optional
    abstract val serveMillis: Property<Long>

    @get:Input
    abstract val environment: MapProperty<String, String>

    @get:Input
    abstract val arguments: ListProperty<String>

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    init {
        // 运行任务永远重新执行；否则输入没变会被判定 UP-TO-DATE，根本不会启动 Host
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val root = runDirectory.get().asFile
        val plugins = root.resolve("plugins")
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

        val host = hostExecutable.orNull?.asFile ?: unpackHost(root)
        val command = mutableListOf(
            "--plugins", plugins.absolutePath,
            "--http=${port.get()}",
        )
        serveMillis.orNull?.let { command += "--serve-ms=$it" }
        command += arguments.get()

        logger.lifecycle("keelRun: ${host.absolutePath} ${command.joinToString(" ")}")
        exec.exec {
            it.executable = host.absolutePath
            it.workingDir = root
            it.args = command
            it.environment(environment.get())
            it.standardOutput = System.out
            it.errorOutput = System.err
        }
    }

    private fun unpackHost(root: File): File {
        val archive = hostArchive.files.firstOrNull()
            ?: error(
                "找不到 Host：配置 keelPlugin.runHostExecutable，或先发布 " +
                    "moe.lolosia.keel:keel-host-dev-<target>:<keelVersion>@zip"
            )
        val directory = root.resolve("host")
        fs.delete { it.delete(directory) }
        fs.copy {
            it.from(archives.zipTree(archive))
            it.into(directory)
        }
        return directory.walkTopDown()
            .firstOrNull { it.isFile && it.nameWithoutExtension == "keel-host" }
            ?: error("Host 包里没有 keel-host：$archive")
    }
}
