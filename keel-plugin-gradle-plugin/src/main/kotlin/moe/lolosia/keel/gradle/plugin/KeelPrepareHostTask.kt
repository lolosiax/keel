package moe.lolosia.keel.gradle.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * 把运行用的 Host 解到 `build/keel-run/host`，不启动它。
 *
 * 与 [KeelPrepareRunTask] 分开是为了避免每次改插件都重写 Host 可执行文件：
 * Windows 上正在运行的 Host 会占用文件，重复解包会直接失败。
 */
abstract class KeelPrepareHostTask : DefaultTask() {

    /** 从 Maven 解析到的 Host 包（`keel-host-dev-<target>@zip`）。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hostArchive: ConfigurableFileCollection

    /** 本地 Host 可执行文件；优先于 [hostArchive]。 */
    @get:InputFile
    @get:Optional
    abstract val hostExecutable: RegularFileProperty

    @get:OutputDirectory
    abstract val hostDirectory: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun prepare() {
        val directory = hostDirectory.get().asFile
        fs.delete { it.delete(directory) }
        directory.mkdirs()

        val explicit = hostExecutable.orNull?.asFile
        if (explicit != null) {
            explicit.copyTo(directory.resolve(explicit.name), overwrite = true)
            return
        }

        val archive = hostArchive.files.firstOrNull()
            ?: error(
                "找不到 Host：配置 keelPlugin.runHostExecutable，或先发布 " +
                    "moe.lolosia.keel:keel-host-dev-<target>:<keelVersion>@zip"
            )
        fs.copy {
            it.from(archives.zipTree(archive))
            it.into(directory)
        }
    }
}
