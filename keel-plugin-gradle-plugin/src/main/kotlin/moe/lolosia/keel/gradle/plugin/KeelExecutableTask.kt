package moe.lolosia.keel.gradle.plugin

import moe.lolosia.keel.gradle.classpath.KeelEmbeddedResource
import moe.lolosia.keel.gradle.classpath.KeelWindowsResources
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.process.ExecOperations
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * 把 `keel-bundle.zip` 注入预编译 launcher，产出单文件。
 *
 * - Windows：生成 `.rc` → `windres -O res` → `UpdateResource` 注入 PE；
 * - Linux：launcher + bundle + 32 字节 footer（`KEELBNDL`）。
 */
@DisableCachingByDefault(because = "产物是注入过资源的可执行文件，绑定当前宿主工具链")
abstract class KeelExecutableTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundle: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launcherArtifacts: ConfigurableFileCollection

    @get:Input
    abstract val target: Property<String>

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val productName: Property<String>

    @get:Input
    abstract val fileDescription: Property<String>

    @get:Input
    abstract val companyName: Property<String>

    @get:Input
    abstract val copyright: Property<String>

    @get:Input
    @get:Optional
    abstract val icon: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val targetName = target.get()
        val launcher = resolveLauncher()
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        when {
            targetName.startsWith("mingw") -> buildWindows(launcher, output)
            targetName.startsWith("linux") -> buildLinux(launcher, output)
            else -> throw GradleException("keelExecutable 暂不支持目标 $targetName")
        }
    }

    private fun resolveLauncher(): File {
        val candidates = launcherArtifacts.files.filter { it.isFile }
        if (candidates.isEmpty()) {
            throw GradleException(
                "keelPlugin.executable 未解析到 launcher；请先发布 keel-launcher，" +
                    "或配置 keelPlugin.executable.launcher = file(...)"
            )
        }
        val download = candidates.firstOrNull { isZip(it) } ?: candidates.first()
        if (!isZip(download)) return download

        val dir = temporaryDir.resolve("launcher").apply { mkdirs() }
        ZipFile(download).use { zip ->
            val entry = zip.entries().toList()
                .filter { !it.isDirectory && it.name.substringAfterLast('/').startsWith("keel") }
                .minByOrNull { it.name }
                ?: throw GradleException("${download.name} 里没有 keel* launcher")
            val target = dir.resolve(entry.name.substringAfterLast('/'))
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return target
        }
    }

    private fun buildWindows(launcher: File, output: File) {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("win")) {
            throw GradleException(
                "Windows PE 资源注入只能在 Windows 宿主上执行；当前宿主：$os"
            )
        }

        val dir = temporaryDir.resolve("win").apply { mkdirs() }
        val exe = dir.resolve("app.exe")
        launcher.copyTo(exe, overwrite = true)

        val iconPath = icon.orNull?.takeIf { it.isNotBlank() }?.let { File(it) }
        val iconFile = dir.resolve("app.ico")
        if (iconPath != null) {
            iconPath.copyTo(iconFile, overwrite = true)
        } else {
            KeelWindowsResources.extractDefaultIcon(iconFile)
        }

        bundle.get().asFile.copyTo(dir.resolve("keel-bundle.zip"), overwrite = true)

        val rc = dir.resolve("app.rc")
        rc.writeText(
            KeelWindowsResources.buildScript(
                embedded = listOf(
                    KeelEmbeddedResource(
                        id = "IDR_KEEL_BUNDLE",
                        type = "KEELBUNDLE",
                        file = "keel-bundle.zip",
                    )
                ),
                icon = "app.ico",
                version = version.get(),
                productName = productName.get(),
                fileDescription = fileDescription.get(),
                companyName = companyName.get(),
                copyright = copyright.get(),
                internalName = appName.get(),
                executableName = output.name,
            ),
            Charsets.UTF_8,
        )

        val res = dir.resolve("app.res")
        val binDir = KeelWindowsResources.konanMingwBinDir()
        execOperations.exec {
            it.workingDir = dir
            it.commandLine(
                File(binDir, "windres").absolutePath,
                "--codepage=65001",
                "-O", "res",
                "-o", res.name,
                rc.name,
            )
            it.environment(
                "PATH",
                "${binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH").orEmpty()}",
            )
        }

        KeelWindowsResourceInjector.inject(exe, res)
        exe.copyTo(output, overwrite = true)
    }

    private fun buildLinux(launcher: File, output: File) {
        val launcherBytes = launcher.readBytes()
        val bundleBytes = bundle.get().asFile.readBytes()
        val crc = CRC32().apply { update(bundleBytes) }.value

        val footer = ByteBuffer.allocate(FOOTER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(launcherBytes.size.toLong())
            putLong(bundleBytes.size.toLong())
            putInt(FORMAT_VERSION)
            putInt(crc.toInt())
            put(FOOTER_MAGIC.toByteArray(Charsets.US_ASCII))
        }.array()

        FileOutputStream(output).use { stream ->
            stream.write(launcherBytes)
            stream.write(bundleBytes)
            stream.write(footer)
        }
    }

    private fun isZip(file: File): Boolean {
        if (file.length() < 4) return false
        return file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 &&
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        }
    }

    private companion object {
        const val FOOTER_SIZE = 32
        const val FORMAT_VERSION = 1
        const val FOOTER_MAGIC = "KEELBNDL"
    }
}
