package moe.lolosia.keel.gradle.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * `keelRun`：启动已经由 [KeelPrepareRunTask] 组装好的 Host。
 *
 * 等价于 Bukkit 的 `runServer` / Forge 的 `runServer`：插件没有独立进程，必须由宿主加载。
 * 这是一个**始终执行**的任务（不参与 up-to-date），因为它的目的就是跑起来。
 *
 * 构建与运行分开：本任务不再打包或解包，只负责 exec；
 * 目录布局由 `keelPrepareHost` / `keelPrepareRun` 准备：
 * ```
 * build/keel-run/
 *   host/keel-host[.exe]            # Maven 解析出的 Host 包解压结果
 *   plugins/<id>.keel               # 本插件 + 能力包
 *   plugins/.cache/config/plugins/  # HostConfigs 的 workDir 是 cacheDir
 *   davroot/ ...                    # dev/data 复制过来的运行期文件
 * ```
 */
@DisableCachingByDefault(because = "运行任务只负责启动 Host，不产出可复用结果")
abstract class KeelRunTask : DefaultTask() {

    /** `keelPrepareRun` 组装好的运行目录。 */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
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

    init {
        // 运行任务永远重新执行；否则输入没变会被判定 UP-TO-DATE，根本不会启动 Host
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val root = runDirectory.get().asFile
        val host = findHost(root)
        val plugins = root.resolve("plugins")
        val command = mutableListOf(
            "--plugins", plugins.absolutePath,
            "--http=${port.get()}",
        )
        serveMillis.orNull?.let { command += "--serve-ms=$it" }
        command += arguments.get()

        val env = environment.get().toMutableMap()
        // Gradle 的 exec 会把子进程 stdout 接到管道，Host 的 ANSI 探测会因此关闭；
        // 显式强制颜色，让 IDEA / 终端都能看到着色的日志。
        val colorDisabled = env.entries.any { (name, value) ->
            name.equals("NO_COLOR", ignoreCase = true) ||
                (name.equals("CLICOLOR", ignoreCase = true) && value == "0")
        } || System.getenv("NO_COLOR") != null
        if (!colorDisabled && env.keys.none { it.equals("FORCE_COLOR", ignoreCase = true) }) {
            env["FORCE_COLOR"] = "1"
        }

        logger.lifecycle("keelRun: ${host.absolutePath} ${command.joinToString(" ")}")
        exec.exec {
            it.executable = host.absolutePath
            it.workingDir = root
            it.args = command
            it.environment(env)
            it.standardOutput = System.out
            it.errorOutput = System.err
        }
    }

    /** Host 由 prepare 任务解到运行目录下；加载器按实际文件名扫描，不写死扩展名。 */
    private fun findHost(root: File): File =
        root.resolve("host").walkTopDown()
            .firstOrNull { it.isFile && it.nameWithoutExtension == "keel-host" }
            ?: error("运行目录里没有 keel-host，请先执行 keelPrepareHost：${root.absolutePath}")
}
