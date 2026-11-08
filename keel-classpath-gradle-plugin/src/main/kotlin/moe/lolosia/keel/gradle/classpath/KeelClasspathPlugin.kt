package moe.lolosia.keel.gradle.classpath

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

/**
 * 普通 Kotlin/Native 产物的 `classpath.zip` 嵌入流水线。
 *
 * - 资源合并顺序沿用 backend：先入者优先；
 * - Windows：windres 编译 PE 资源；
 * - Linux：objcopy 生成目标文件，经 `_binary_classpath_zip_start/_end` 暴露；
 * - 插件（`.keel`）不走这条链路，资源在包内 `resources/`。
 */
class KeelClasspathPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("keelClasspath", KeelClasspathExtension::class.java)

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate {
                if (!extension.enabled) return@afterEvaluate
                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                val targets = kotlin.targets.withType(KotlinNativeTarget::class.java)
                    .filter { extension.targets.isEmpty() || it.name in extension.targets }
                targets.forEach { configureTarget(project, it, extension) }
            }
        }
    }

    private fun configureTarget(
        project: Project,
        target: KotlinNativeTarget,
        extension: KeelClasspathExtension,
    ) {
        val suffix = target.name.replaceFirstChar { it.uppercaseChar() }
        val embedDir = project.layout.buildDirectory
            .dir("generated/keel/classpath/${target.name}")
            .get().asFile
        val executableName = target.binaries
            .withType(Executable::class.java)
            .firstOrNull()
            ?.baseName
            ?: project.name

        val zipTask = if (extension.embedClasspath) {
            registerClasspathZip(project, target, suffix, embedDir, extension)
        } else {
            null
        }

        val embed = when {
            target.name.startsWith("mingw") ->
                embedAsWindowsResource(
                    project, suffix, zipTask, embedDir, extension.windows, executableName,
                )

            target.name.startsWith("linux") && zipTask != null ->
                embedAsLinuxObject(project, target, suffix, zipTask, embedDir, extension)

            zipTask == null -> return

            else -> {
                project.logger.warn(
                    "classpath 嵌入暂不支持目标 ${target.name}：资源不会进入可执行文件"
                )
                ClasspathEmbed(zipTask, embedDir.resolve(CLASSPATH_ZIP_NAME), emptyList())
            }
        }

        target.binaries.withType(Executable::class.java).configureEach { binary ->
            binary.linkerOpts.addAll(embed.linkerArgs)
            binary.linkTaskProvider.configure { linkTask ->
                linkTask.dependsOn(embed.steps)
                linkTask.inputs.files(embed.artifact)
            }
        }
    }

    private fun registerClasspathZip(
        project: Project,
        target: KotlinNativeTarget,
        suffix: String,
        embedDir: File,
        extension: KeelClasspathExtension,
    ): TaskProvider<Zip> {
        val zipTask = project.tasks.register("keelClasspathZip$suffix", Zip::class.java) { task ->
            task.group = "keel"
            task.description = "把 ${target.name} 的资源合并成 classpath.zip"
            task.archiveFileName.set(CLASSPATH_ZIP_NAME)
            task.destinationDirectory.set(embedDir)
            task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            task.entryCompression =
                if (extension.compressed) ZipEntryCompression.DEFLATED else ZipEntryCompression.STORED
            task.isPreserveFileTimestamps = false
            task.isReproducibleFileOrder = true

            resourceSourceSetsOf(target).forEach { sourceSet ->
                task.from(sourceSet.resources, Action<CopySpec> { it.into("") })
            }
            task.from(extension.resources.map { project.file(it) }, Action<CopySpec> { it.into("") })
        }

        project.gradle.projectsEvaluated {
            dependencyClasspathResources(project, target.name).forEach { resource ->
                zipTask.configure { task ->
                    task.from(resource, Action<CopySpec> { it.into("") })
                }
            }
        }
        return zipTask
    }

    private fun embedAsWindowsResource(
        project: Project,
        suffix: String,
        zipTask: TaskProvider<Zip>?,
        embedDir: File,
        windows: KeelWindowsExtension,
        executableName: String,
    ): ClasspathEmbed {
        val version = project.version.toString()
        val productName = windows.productName.ifBlank { project.name }
        val fileDescription = windows.fileDescription.ifBlank { productName }
        // 留空用插件内置的默认图标，保证不配置也有图标
        val iconSource = windows.icon.takeIf { it.isNotBlank() }?.let { project.file(it) }

        val rcTask = project.tasks.register("keelClasspathRc$suffix") { task ->
            task.group = "keel"
            zipTask?.let { task.dependsOn(it) }
            val rc = embedDir.resolve(RC_FILE)
            task.inputs.property("version", version)
            task.inputs.property("productName", productName)
            task.inputs.property("fileDescription", fileDescription)
            task.inputs.property("companyName", windows.companyName)
            task.inputs.property("copyright", windows.copyright)
            task.inputs.property("executableName", executableName)
            task.inputs.property("embedClasspath", zipTask != null)
            task.inputs.property("defaultIcon", iconSource == null)
            iconSource?.let { task.inputs.file(it) }
            task.outputs.file(rc)
            task.doLast {
                embedDir.mkdirs()
                val icon = embedDir.resolve(ICON_FILE)
                if (iconSource != null) iconSource.copyTo(icon, overwrite = true) else extractDefaultIcon(icon)
                rc.writeText(
                    buildWindowsResourceScript(
                        embedClasspath = zipTask != null,
                        icon = ICON_FILE,
                        version = version,
                        productName = productName,
                        fileDescription = fileDescription,
                        companyName = windows.companyName,
                        copyright = windows.copyright,
                        internalName = executableName,
                        executableName = "$executableName.exe",
                    ),
                    Charsets.UTF_8,
                )
            }
        }

        val windresTask = project.tasks.register("keelClasspathWindres$suffix", Exec::class.java) { task ->
            task.group = "keel"
            task.dependsOn(rcTask)
            zipTask?.let { task.dependsOn(it) }
            task.workingDir = embedDir
            task.inputs.file(embedDir.resolve(RC_FILE))
            zipTask?.let { task.inputs.file(embedDir.resolve(CLASSPATH_ZIP_NAME)) }
            task.outputs.file(embedDir.resolve(CLASSPATH_RES))

            val binDir = konanMingwBinDir()
            task.commandLine(
                File(binDir, "windres").absolutePath,
                // 版本信息里可能有中文，资源脚本按 UTF-8 写，windres 也要按 UTF-8 读
                "--codepage=65001",
                RC_FILE, "-O", "coff", "-o", CLASSPATH_RES,
            )
            task.environment(
                "PATH",
                "${binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}",
            )
        }

        val resource = embedDir.resolve(CLASSPATH_RES)
        return ClasspathEmbed(windresTask, resource, listOf(resource.absolutePath))
    }

    private fun embedAsLinuxObject(
        project: Project,
        target: KotlinNativeTarget,
        suffix: String,
        zipTask: TaskProvider<Zip>,
        embedDir: File,
        extension: KeelClasspathExtension,
    ): ClasspathEmbed {
        val config = objcopyConfigFor(target.name)
        if (config == null) {
            project.logger.warn(
                "未配置 ${target.name} 的 objcopy：资源不会进入可执行文件"
            )
            return ClasspathEmbed(zipTask, embedDir.resolve(CLASSPATH_ZIP_NAME), emptyList())
        }

        val useWsl = Os.isFamily(Os.FAMILY_WINDOWS)
        val objcopyTask = project.tasks.register("keelClasspathObjcopy$suffix", Exec::class.java) { task ->
            task.group = "keel"
            task.dependsOn(zipTask)
            task.inputs.file(embedDir.resolve(CLASSPATH_ZIP_NAME))
            task.outputs.file(embedDir.resolve(CLASSPATH_OBJECT))

            val args = listOf(
                config.tool,
                "-I", "binary", "-O", config.outputFormat, "-B", config.architecture,
                CLASSPATH_ZIP_NAME, CLASSPATH_OBJECT,
            )
            if (useWsl) {
                task.commandLine(
                    listOf("wsl") + wslOptions(extension) +
                        listOf("--", "bash", "-c", "cd ${toWslPath(embedDir)} && ${args.joinToString(" ")}")
                )
            } else {
                task.workingDir = embedDir
                task.commandLine(args)
            }
        }

        val obj = embedDir.resolve(CLASSPATH_OBJECT)
        return ClasspathEmbed(
            objcopyTask,
            obj,
            listOf("-Wl,--export-dynamic", obj.absolutePath),
        )
    }

    private fun resourceSourceSetsOf(target: KotlinNativeTarget): List<KotlinSourceSet> {
        val ordered = mutableListOf<KotlinSourceSet>()

        fun visit(sourceSet: KotlinSourceSet) {
            if (ordered.any { it.name == sourceSet.name }) return
            ordered += sourceSet
            sourceSet.dependsOn.forEach { visit(it) }
        }

        visit(target.compilations.getByName("main").defaultSourceSet)
        return ordered
    }

    private fun dependencyClasspathResources(project: Project, targetName: String): List<Any> {
        val result = mutableListOf<Any>()
        val visitedProjects = mutableSetOf<String>()
        val visitedSourceSets = mutableSetOf<String>()

        fun visit(current: Project) {
            if (current == project || !visitedProjects.add(current.path)) return

            val kotlin = current.extensions.findByType(KotlinMultiplatformExtension::class.java)
            val target = kotlin?.targets?.findByName(targetName) as? KotlinNativeTarget
            if (target != null) {
                resourceSourceSetsOf(target).forEach { sourceSet ->
                    if (visitedSourceSets.add("${current.path}:${sourceSet.name}")) {
                        result.add(sourceSet.resources)
                    }
                }
            }

            projectDependenciesOf(current).forEach { visit(it) }
        }

        projectDependenciesOf(project).forEach { visit(it) }
        return result
    }

    private fun projectDependenciesOf(project: Project): List<Project> =
        project.configurations
            .flatMap { configuration ->
                configuration.dependencies.withType(ProjectDependency::class.java)
            }
            .mapNotNull { dependency -> project.findProject(dependency.path) }
            .distinctBy { it.path }

    private fun wslOptions(extension: KeelClasspathExtension): List<String> = buildList {
        extension.wslDistro?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-d", it)) }
        extension.wslUser?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-u", it)) }
    }

    private fun toWslPath(file: File): String {
        val path = file.absolutePath
        val drive = path.substring(0, 1).lowercase()
        val rest = path.substring(2).replace('\\', '/')
        return "/mnt/$drive$rest"
    }

    /**
     * 生成 windres 用的 `.rc`。
     *
     * 版本号取 Gradle 的 `version`（FILEVERSION 只认 4 段数字，`-SNAPSHOT` 这类后缀会被丢掉），
     * 文件内容按 UTF-8 写，windres 侧用 `--codepage=65001` 读。
     */
    private fun buildWindowsResourceScript(
        embedClasspath: Boolean,
        icon: String,
        version: String,
        productName: String,
        fileDescription: String,
        companyName: String,
        copyright: String,
        internalName: String,
        executableName: String,
    ): String {
        val quad = winVersionParts(version).joinToString(",")
        val quote = { value: String -> value.replace("\"", "'") }

        return buildString {
            appendLine("// 由 Gradle 生成，不要手改：版本号取工程 version，名称来自 keelClasspath { windows { … } }。")
            appendLine("// 文件本身是 UTF-8，windres 以 --codepage=65001 读取。")
            if (embedClasspath) {
                appendLine()
                appendLine("// 内嵌 classpath.zip（自定义资源类型），名字与 EmbeddedClasspath.mingw.kt 的查找逻辑对应。")
                appendLine("IDR_CLASSPATH  CLASSPATH  \"$CLASSPATH_ZIP_NAME\"")
            }
            appendLine()
            appendLine("// 应用图标；未配置 keelClasspath { windows { icon = … } } 时用插件内置的默认图标。")
            appendLine("1 ICON \"$icon\"")
            appendLine()
            appendLine("1 VERSIONINFO")
            appendLine("FILEVERSION $quad")
            appendLine("PRODUCTVERSION $quad")
            appendLine("FILEFLAGSMASK 0x3fL")
            appendLine("FILEFLAGS 0x0L")
            appendLine("FILEOS 0x40004L")
            appendLine("FILETYPE 0x1L")
            appendLine("FILESUBTYPE 0x0L")
            appendLine("BEGIN")
            appendLine("  BLOCK \"StringFileInfo\"")
            appendLine("  BEGIN")
            appendLine("    BLOCK \"040904b0\"")
            appendLine("    BEGIN")
            if (companyName.isNotBlank()) {
                appendLine("      VALUE \"CompanyName\", \"${quote(companyName)}\"")
            }
            appendLine("      VALUE \"FileDescription\", \"${quote(fileDescription)}\"")
            appendLine("      VALUE \"FileVersion\", \"${quote(version)}\"")
            appendLine("      VALUE \"InternalName\", \"${quote(internalName)}\"")
            if (copyright.isNotBlank()) {
                appendLine("      VALUE \"LegalCopyright\", \"${quote(copyright)}\"")
            }
            appendLine("      VALUE \"OriginalFilename\", \"${quote(executableName)}\"")
            appendLine("      VALUE \"ProductName\", \"${quote(productName)}\"")
            appendLine("      VALUE \"ProductVersion\", \"${quote(version)}\"")
            appendLine("    END")
            appendLine("  END")
            appendLine("  BLOCK \"VarFileInfo\"")
            appendLine("  BEGIN")
            appendLine("    VALUE \"Translation\", 0x409, 1200")
            appendLine("  END")
            appendLine("END")
        }
    }

    /** `1.0.0-SNAPSHOT` → `[1, 0, 0, 0]`：FILEVERSION 只接受 4 段数字。 */
    private fun winVersionParts(version: String): List<Int> {
        val numeric = version.substringBefore('-').substringBefore('+')
        val parts = numeric.split('.').map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return (parts + List(4) { 0 }).take(4)
    }

    /** 把插件内置的默认图标释放到构建目录，供 windres 读取。 */
    private fun extractDefaultIcon(target: File) {
        val stream = KeelClasspathPlugin::class.java.getResourceAsStream(DEFAULT_ICON_RESOURCE)
            ?: error("插件 jar 内找不到默认图标：$DEFAULT_ICON_RESOURCE")
        stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun konanMingwBinDir(): File {
        val konanDataDir = System.getenv("KONAN_DATA_DIR")
            ?: "${System.getProperty("user.home")}/.konan"
        val binDir = File(konanDataDir, "dependencies/msys2-mingw-w64-x86_64-2/bin")
        check(binDir.isDirectory) {
            "未找到 Kotlin/Native 自带的 mingw 工具链：$binDir\n" +
                "请先执行一次 mingwX64 链接（会自动下载工具链），或检查 KONAN_DATA_DIR。"
        }
        return binDir
    }

    private data class ObjcopyConfig(
        val tool: String,
        val outputFormat: String,
        val architecture: String,
    )

    private fun objcopyConfigFor(targetName: String): ObjcopyConfig? = when (targetName) {
        "linuxArm64" -> ObjcopyConfig("aarch64-linux-gnu-objcopy", "elf64-littleaarch64", "aarch64")
        "linuxX64" -> ObjcopyConfig("x86_64-linux-gnu-objcopy", "elf64-x86-64", "i386:x86-64")
        else -> null
    }

    private class ClasspathEmbed(
        val steps: TaskProvider<*>,
        val artifact: File,
        val linkerArgs: List<String>,
    )

    private companion object {
        const val CLASSPATH_ZIP_NAME = "classpath.zip"
        const val CLASSPATH_RES = "classpath.res"
        const val CLASSPATH_OBJECT = "classpath.o"
        const val RC_FILE = "classpath.rc"
        const val ICON_FILE = "keel-icon.ico"
        const val DEFAULT_ICON_RESOURCE = "/moe/lolosia/keel/gradle/classpath/keel-icon.ico"
    }
}
