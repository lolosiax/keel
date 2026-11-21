package moe.lolosia.keel.gradle.thirdparty

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把已编译好的原生库按 manifest 打包成 Maven 工件（zip），并附带声明 / SBOM / 上游来源。
 *
 * 输入目录约定：`<publishFrom>/<lib>/<target>/include/` 与
 * `<publishFrom>/<lib>/<target>/lib/`；`.def` 与许可文本取仓库里的
 * `thirdparty/<lib>/`。没有编译产物时直接失败，不产出空包。
 */
@DisableCachingByDefault(because = "打包结果与本地编译产物绑定，不适合跨机器复用")
abstract class KeelThirdpartyPublishTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    /** 缺失时在 action 里报错（而不是被 Gradle 的输入校验提前拦下）。 */
    @get:Internal
    abstract val publishFrom: DirectoryProperty

    /** 只用于解析不在 `thirdparty/` 下的许可文件，不做整目录快照。 */
    @get:Internal
    abstract val projectDir: DirectoryProperty

    /** 只发布这些库（逗号分隔的 manifest 键）；为空表示全部。 */
    @get:Input
    @get:Optional
    abstract val libraries: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun publish() {
        val file = manifestFile.orNull?.asFile
            ?: throw GradleException("没有 $KeelThirdpartyManifest.DEFAULT_PATH，无法发布原生库")
        val document = KeelThirdpartyManifest.read(file)
        if (document.libraries.isEmpty()) {
            throw GradleException("manifest 里没有库，没有可发布的工件：$file")
        }
        val selected = libraries.getOrElse(emptyList())
        val targets = document.libraries.filterKeys { selected.isEmpty() || it in selected }
        if (targets.isEmpty()) {
            throw GradleException("keel.thirdparty.libs=${selected.joinToString()} 没有匹配的库：$file")
        }

        val sourceRoot = file.parentFile
        val from = publishFrom.get().asFile
        val project = projectDir.get().asFile
        val output = outputDirectory.get().asFile
        val problems = mutableListOf<String>()
        // 通知 / SBOM 只列本次发布的库，避免写进未发布的依赖
        val published = document.copy(libraries = targets)

        targets.values.sortedBy { it.name }.forEach { library ->
            library.libs.keys.sorted().forEach { target ->
                val staging = File(from, "${library.name}/$target")
                val includeDir = File(staging, "include")
                val libDir = File(staging, "lib")
                if (!includeDir.isDirectory || !libDir.isDirectory) {
                    problems += "${library.name}@$target：缺少编译产物（期望 $includeDir 与 $libDir）"
                    return@forEach
                }

                val defSource = File(sourceRoot, "${library.name}/${library.def}")
                if (!defSource.isFile) {
                    problems += "${library.name}@$target：缺少 .def（$defSource）"
                    return@forEach
                }

                val artifactId = "${library.name}-$target"
                val version = library.artifactVersion
                val zip = File(
                    output,
                    "${document.groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.zip",
                )
                writePackage(
                    zip = zip,
                    includeDir = includeDir,
                    libDir = libDir,
                    target = target,
                    defSource = defSource,
                    library = library,
                    document = published,
                    sourceRoot = sourceRoot,
                    projectDir = project,
                )
                logger.lifecycle("keel-thirdparty: 已打包 ${zip.relativeTo(output).invariantSeparatorsPath}")
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "第三方库发布失败：\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
    }

    private fun writePackage(
        zip: File,
        includeDir: File,
        libDir: File,
        target: String,
        defSource: File,
        library: KeelThirdpartyManifest.Library,
        document: KeelThirdpartyManifest.Document,
        sourceRoot: File,
        projectDir: File,
    ) {
        zip.parentFile.mkdirs()
        val entries = linkedMapOf<String, ByteArray>()
        collectTree(includeDir, "include").forEach { (path, file) -> entries[path] = file.readBytes() }
        collectTree(libDir, "lib/$target").forEach { (path, file) -> entries[path] = file.readBytes() }
        entries["def/${library.def}"] = defSource.readBytes()

        library.licenseFiles.forEach { path ->
            val license = resolveLicenseFile(sourceRoot, projectDir, path)
            if (!license.isFile) throw GradleException("找不到许可文件：$path")
            entries["licenses/${library.name}/${license.name}"] = license.readBytes()
        }

        entries["THIRD-PARTY-NOTICES.md"] =
            KeelThirdpartyNotices.markdown(document).toByteArray()
        entries["SBOM.json"] = KeelThirdpartyNotices.sbom(document).toByteArray()
        entries["upstream-source.txt"] =
            KeelThirdpartyNotices.upstreamSource(library).toByteArray()

        ZipOutputStream(BufferedOutputStream(zip.outputStream())).use { stream ->
            entries.keys.sorted().forEach { name ->
                val entry = ZipEntry(name)
                entry.time = 0
                stream.putNextEntry(entry)
                stream.write(entries.getValue(name))
                stream.closeEntry()
            }
        }
    }

    private fun collectTree(root: File, prefix: String): List<Pair<String, File>> = buildList {
        val base = root.toPath()
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { file ->
                val relative = base.relativize(file.toPath()).toString().replace('\\', '/')
                add("$prefix/$relative" to file)
            }
    }
}

private fun resolveLicenseFile(sourceRoot: File, projectDir: File, path: String): File {
    val inSource = File(sourceRoot, path)
    if (inSource.isFile) return inSource
    return File(projectDir, path)
}
