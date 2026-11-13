package moe.lolosia.keel.gradle.classpath

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * 对单个原生二进制执行 `strip --strip-unneeded`，输出到独立目录。
 *
 * Kotlin/Native 的 release 产物仍然保留 COFF / ELF 符号表（mingw 实测约 1–2MB），
 * `linkerOpts("-s")` 在这条链路上不生效，所以在打包前显式 strip。
 * 导出表由 `.def` 指定，strip 不会影响 `GetProcAddress`。
 *
 * mingw 用 K/N 自带的 msys2 `strip.exe`；Linux 目标在对应平台上用交叉工具链的 strip，
 * 在 Windows 上构建 Linux 目标时跳过（交叉 strip 是 ELF，跑不起来）。
 */
@DisableCachingByDefault(because = "strip 结果与宿主工具链绑定，跨机器复用的价值不大")
abstract class KeelStripTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFile: RegularFileProperty

    @get:Input
    abstract val target: Property<String>

    /** 输出目录；文件名与输入保持一致，方便 `.keel` 里的 `lib/<target>/<name>` 直接引用。 */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun strip() {
        val input = inputFile.get().asFile
        val output = File(outputDirectory.get().asFile, input.name)
        output.parentFile.mkdirs()
        input.copyTo(output, overwrite = true)

        val tool = stripTool(target.get())
        if (tool == null) {
            logger.warn("keel strip: 当前宿主无法处理 ${target.get()}，跳过 ${input.name}")
            return
        }
        execOperations.exec {
            it.commandLine(tool.absolutePath, "--strip-unneeded", output.absolutePath)
        }
    }

    private fun stripTool(target: String): File? {
        if (target.startsWith("mingw")) {
            return runCatching {
                File(KeelWindowsResources.konanMingwBinDir(), "strip.exe").takeIf { it.isFile }
            }.getOrElse {
                logger.warn("keel strip: 未找到 mingw strip（${it.message}），跳过 ${inputFile.get().asFile.name}")
                null
            }
        }
        if (System.getProperty("os.name").lowercase().contains("win")) return null

        val prefix = if (target.startsWith("linuxArm64")) "aarch64-unknown-linux-gnu" else "x86_64-unknown-linux-gnu"
        val konanRoot = System.getenv("KONAN_DATA_DIR")
            ?: "${System.getProperty("user.home")}/.konan"
        val cross = File("$konanRoot/dependencies/$prefix-gcc/bin/$prefix-strip")
        if (cross.isFile) return cross
        return File("/usr/bin/strip").takeIf { it.isFile }
    }
}
